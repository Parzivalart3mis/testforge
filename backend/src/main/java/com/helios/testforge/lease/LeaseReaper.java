package com.helios.testforge.lease;

import com.helios.testforge.config.TestForgeProperties;
import com.helios.testforge.domain.lease.Lease;
import com.helios.testforge.domain.lease.LeaseState;
import com.helios.testforge.persistence.LeaseRepository;
import com.helios.testforge.provision.EphemeralDatabaseProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drops databases whose leases have expired.
 *
 * <p>Two sweeps, because they fail differently:
 *
 * <ul>
 *   <li>The <b>expiry sweep</b> handles the normal case — a lease reached its
 *       TTL and nobody renewed it. The state transition is claimed in the
 *       database before the drop, so two service instances running the reaper
 *       concurrently cannot both drop the same database.</li>
 *   <li>The <b>orphan sweep</b> handles the abnormal case — a database exists on
 *       the cluster that no active lease accounts for, because a crash landed
 *       between CREATE DATABASE and the lease insert. Without it those
 *       databases would accumulate forever, since nothing would ever expire
 *       them.</li>
 * </ul>
 *
 * <p>Both refuse to touch any name lacking the configured prefix.
 */
@Component
@ConditionalOnProperty(name = "testforge.leases.reaper-enabled", havingValue = "true", matchIfMissing = true)
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    /** Leases reclaimed per sweep, so one large backlog cannot monopolise the cluster. */
    private static final int BATCH_LIMIT = 50;

    /**
     * How long a database must be unaccounted for before the orphan sweep drops
     * it. Comfortably longer than a provisioning run, so a database that is
     * mid-creation is never mistaken for an orphan.
     */
    private static final java.time.Duration ORPHAN_GRACE = java.time.Duration.ofMinutes(30);

    private final LeaseRepository repository;
    private final EphemeralDatabaseProvisioner provisioner;
    private final TestForgeProperties.Leases config;

    private final AtomicLong reclaimed = new AtomicLong();
    private final AtomicLong orphansDropped = new AtomicLong();

    /** Databases seen unaccounted-for, and when. Only dropped once they persist across the grace period. */
    private final java.util.Map<String, Instant> suspectedOrphans = new java.util.concurrent.ConcurrentHashMap<>();

    public LeaseReaper(LeaseRepository repository,
                       EphemeralDatabaseProvisioner provisioner,
                       TestForgeProperties properties) {
        this.repository = repository;
        this.provisioner = provisioner;
        this.config = properties.leases();
    }

    /** Reclaims leases past their expiry plus the grace period. */
    @Scheduled(fixedDelayString = "${testforge.leases.reaper-interval:PT1M}")
    public void reapExpiredLeases() {
        Instant cutoff = Instant.now().minus(config.reapGrace());
        List<Lease> expired = repository.findReapable(cutoff, BATCH_LIMIT);
        if (expired.isEmpty()) {
            return;
        }

        int dropped = 0;
        for (Lease lease : expired) {
            // Claim the transition first. Losing this race means another
            // instance is already handling the lease.
            if (!repository.close(lease.id(), LeaseState.EXPIRED, Instant.now())) {
                continue;
            }
            try {
                provisioner.drop(lease.databaseName(), lease.username());
                dropped++;
                reclaimed.incrementAndGet();
            } catch (RuntimeException e) {
                log.error("Expired lease {} but could not drop {}: {}. "
                                + "The orphan sweep will retry it.",
                        lease.id(), lease.databaseName(), e.getMessage());
            }
        }
        log.info("Reaper reclaimed {} of {} expired leases", dropped, expired.size());
    }

    /**
     * Drops databases carrying the platform's prefix that no active lease
     * accounts for.
     *
     * <p>A database is only dropped after it has been unaccounted for across two
     * consecutive sweeps separated by the grace period, so a database being
     * created right now — visible on the cluster, not yet in the lease table —
     * is never mistaken for garbage.
     */
    @Scheduled(fixedDelayString = "${testforge.leases.orphan-sweep-interval:PT10M}")
    public void reapOrphanDatabases() {
        List<String> onCluster;
        try {
            onCluster = provisioner.listOwnedDatabases();
        } catch (RuntimeException e) {
            log.warn("Orphan sweep could not list ephemeral databases: {}", e.getMessage());
            return;
        }

        Set<String> accountedFor = new HashSet<>(repository.activeDatabaseNames());
        Instant now = Instant.now();

        // Anything that reappeared in the lease table is no longer suspect.
        suspectedOrphans.keySet().removeIf(name -> accountedFor.contains(name) || !onCluster.contains(name));

        for (String database : onCluster) {
            if (accountedFor.contains(database)) {
                continue;
            }
            Instant firstSeen = suspectedOrphans.putIfAbsent(database, now);
            if (firstSeen == null) {
                log.info("Database {} has no active lease; will drop it if it is still unaccounted for in {}",
                        database, ORPHAN_GRACE);
                continue;
            }
            if (firstSeen.plus(ORPHAN_GRACE).isAfter(now)) {
                continue;
            }

            try {
                provisioner.drop(database, database + "_r");
                suspectedOrphans.remove(database);
                orphansDropped.incrementAndGet();
                log.warn("Dropped orphaned database {}: no lease has accounted for it since {}",
                        database, firstSeen);
            } catch (RuntimeException e) {
                log.error("Could not drop orphaned database {}: {}", database, e.getMessage());
            }
        }
    }

    /** Leases reclaimed since startup, exposed for metrics and the console's health panel. */
    public long reclaimedCount() {
        return reclaimed.get();
    }

    /** Orphaned databases dropped since startup. */
    public long orphansDroppedCount() {
        return orphansDropped.get();
    }

    /** Databases currently under suspicion, for operational visibility. */
    public Set<String> suspectedOrphanNames() {
        return Set.copyOf(suspectedOrphans.keySet());
    }
}
