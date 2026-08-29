package com.helios.testforge.lease;

import com.helios.testforge.config.TestForgeProperties;
import com.helios.testforge.domain.lease.Lease;
import com.helios.testforge.domain.lease.LeaseState;
import com.helios.testforge.persistence.LeaseRepository;
import com.helios.testforge.provision.EphemeralDatabaseProvisioner;
import com.helios.testforge.provision.ProvisionedDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, extends and reclaims leases on ephemeral databases.
 *
 * <p>A lease is the platform's answer to the question every test data system
 * eventually fails at: who deletes this. Nobody remembers to clean up a
 * database they were given, so every database comes with an expiry from the
 * moment it exists, and the reaper enforces it whether or not anyone remembers.
 * Extending is cheap and self-service; forgetting is free of consequence.
 */
@Service
public class LeaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    private final LeaseRepository repository;
    private final EphemeralDatabaseProvisioner provisioner;
    private final CredentialCipher cipher;
    private final TestForgeProperties.Leases config;

    public LeaseService(LeaseRepository repository,
                        EphemeralDatabaseProvisioner provisioner,
                        CredentialCipher cipher,
                        TestForgeProperties properties) {
        this.repository = repository;
        this.provisioner = provisioner;
        this.cipher = cipher;
        this.config = properties.leases();
    }

    /**
     * Issues a lease over a provisioned database.
     *
     * <p>The returned lease is the only object that ever carries the plaintext
     * password. Every later read returns it redacted, because the control plane
     * stores a digest and cannot reconstruct it.
     *
     * @param requestedTtl the caller's requested TTL, clamped to the configured maximum
     */
    @Transactional
    public Lease issue(ProvisionedDatabase database, String holder, Duration requestedTtl) {
        Duration ttl = clampTtl(requestedTtl);
        Instant now = Instant.now();

        Lease lease = new Lease(
                UUID.randomUUID(),
                database.datasetId(),
                database.databaseName(),
                database.jdbcUrl(),
                database.roleName(),
                database.password(),
                now,
                now.plus(ttl),
                LeaseState.ACTIVE,
                0,
                holder,
                null);

        // Encrypted rather than kept in memory: the request that asked for this
        // dataset returned long ago, and the task that answers the claim may not
        // be the task that provisioned the database.
        repository.insert(lease, digest(database.password()),
                cipher.encrypt(database.password(), lease.id().toString()));
        log.info("Issued lease {} on {} to {}, expiring {}",
                lease.id(), lease.databaseName(), holder, lease.expiresAt());
        return lease;
    }

    /**
     * Hands out a lease's connection string, once.
     *
     * <p>Provisioning is asynchronous, so the connection string cannot be
     * returned by the request that asked for the dataset. It waits here,
     * encrypted, until the requester collects it — and is destroyed on
     * collection. A second call gets nothing, which is deliberate: a credential
     * that can be fetched repeatedly is a credential sitting in every log and
     * browser history that ever touched it.
     *
     * @throws LeaseException when the lease is not active, or the credential has
     *                        already been collected
     */
    @Transactional
    public String claimConnectionString(UUID id) {
        Lease lease = repository.findById(id)
                .orElseThrow(() -> new LeaseException("no lease " + id));

        if (lease.state() != LeaseState.ACTIVE) {
            throw new LeaseException("lease " + id + " is " + lease.state()
                    + ", so it has no usable connection string");
        }

        String ciphertext = repository.claimCredential(id, Instant.now())
                .orElseThrow(() -> new LeaseException("the connection string for lease " + id
                        + " has already been collected. It is shown once and then destroyed; "
                        + "rotate the lease to get a new one."));

        String password = cipher.decrypt(ciphertext, id.toString());
        String separator = lease.jdbcUrl().contains("?") ? "&" : "?";
        return lease.jdbcUrl() + separator + "user=" + lease.username() + "&password=" + password;
    }

    /** Whether a lease's connection string is still waiting to be collected. */
    public boolean hasUnclaimedCredential(UUID id) {
        return repository.hasUnclaimedCredential(id);
    }

    public Optional<Lease> find(UUID id) {
        return repository.findById(id);
    }

    public Optional<Lease> findForDataset(UUID datasetId) {
        return repository.findByDataset(datasetId);
    }

    public List<Lease> list(int limit) {
        return repository.findAll(limit);
    }

    public List<Lease> listForHolder(String holder, int limit) {
        return repository.findByHolder(holder, limit);
    }

    public int activeCount() {
        return repository.countActive();
    }

    /**
     * Extends a lease.
     *
     * @throws LeaseException when the lease is not active, or has been renewed
     *                        as many times as policy allows
     */
    @Transactional
    public Lease renew(UUID id) {
        Lease lease = repository.findById(id)
                .orElseThrow(() -> new LeaseException("no lease " + id));

        if (lease.state() != LeaseState.ACTIVE) {
            throw new LeaseException("lease " + id + " is " + lease.state()
                    + " and cannot be renewed; request a new dataset instead");
        }
        if (!lease.canRenew()) {
            throw new LeaseException("lease " + id + " has been renewed " + lease.renewals()
                    + " times, the maximum. Request a new dataset rather than holding this one indefinitely.");
        }

        Instant newExpiry = latestPermittedExpiry(lease, lease.expiresAt().plus(config.renewBy()));
        if (!repository.renew(id, newExpiry)) {
            throw new LeaseException("lease " + id + " was closed while being renewed");
        }
        log.info("Renewed lease {} until {}", id, newExpiry);
        return repository.findById(id).orElseThrow();
    }

    /** Gives a lease back early and drops its database. */
    @Transactional
    public Lease release(UUID id) {
        return close(id, LeaseState.RELEASED);
    }

    /** Terminates a lease on an operator's behalf. */
    @Transactional
    public Lease revoke(UUID id) {
        return close(id, LeaseState.REVOKED);
    }

    /**
     * Marks a lease failed after provisioning or seeding went wrong, and cleans
     * up whatever partial database was left behind.
     */
    @Transactional
    public void markFailed(UUID id) {
        close(id, LeaseState.FAILED);
    }

    private Lease close(UUID id, LeaseState newState) {
        Lease lease = repository.findById(id)
                .orElseThrow(() -> new LeaseException("no lease " + id));

        if (lease.state() != LeaseState.ACTIVE) {
            // Already closed; releasing twice is not an error worth failing on.
            return lease;
        }
        // The state transition is claimed before the drop, so a concurrent
        // reaper cannot also decide to drop the same database.
        if (!repository.close(id, newState, Instant.now())) {
            return repository.findById(id).orElseThrow();
        }

        try {
            provisioner.drop(lease.databaseName(), lease.username());
        } catch (RuntimeException e) {
            // The lease is already closed, so the database is now an orphan the
            // reaper's sweep will find. Failing here would leave the caller
            // believing the lease is still theirs.
            log.error("Lease {} closed but its database {} could not be dropped: {}",
                    id, lease.databaseName(), e.getMessage());
        }
        log.info("Closed lease {} as {}", id, newState);
        return repository.findById(id).orElseThrow();
    }

    // -------------------------------------------------------------- policy

    /** Clamps a requested TTL to the configured maximum. */
    public Duration clampTtl(Duration requested) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            return config.defaultTtl();
        }
        return requested.compareTo(config.maxTtl()) > 0 ? config.maxTtl() : requested;
    }

    /**
     * A renewal may not push total lifetime past the maximum TTL measured from
     * issue. Without this, repeated renewals would turn a four-hour lease into a
     * permanent database by increments.
     */
    private Instant latestPermittedExpiry(Lease lease, Instant proposed) {
        Instant ceiling = lease.issuedAt().plus(config.maxTtl()).plus(config.renewBy());
        return proposed.isAfter(ceiling) ? ceiling : proposed;
    }

    /**
     * A digest of the password, for forensics only.
     *
     * <p>Nothing authenticates against this — PostgreSQL holds the real
     * credential — so it exists purely so an incident can confirm which
     * credential was issued without the control plane ever being able to
     * reconstruct it.
     */
    static String digest(String password) {
        if (password == null) {
            return "";
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    /** Raised when a lease operation is not permitted in the lease's current state. */
    public static class LeaseException extends RuntimeException {

        public LeaseException(String message) {
            super(message);
        }
    }
}
