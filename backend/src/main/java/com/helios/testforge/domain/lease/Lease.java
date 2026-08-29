package com.helios.testforge.domain.lease;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A time-boxed grant of an ephemeral database.
 *
 * <p>The credential fields are populated only on the path that issues or renews
 * a lease. Every read path loads a lease with {@code password} null and hands
 * back {@link #redactedJdbcUrl()}: the full connection string is shown exactly
 * once, at issue time, and is not retrievable afterwards. A lost connection
 * string is recovered by rotating the lease, not by reading it back.
 *
 * @param id           lease identity, also the handle used to renew and release
 * @param datasetId    the dataset seeded into this database
 * @param databaseName the physical database on the ephemeral cluster
 * @param jdbcUrl      JDBC URL including host, port and database
 * @param username     the role created for this lease
 * @param password     the role's password — non-null only immediately after issue or rotation
 * @param issuedAt     when the lease was granted
 * @param expiresAt    when the reaper becomes eligible to drop the database
 * @param state        current lifecycle state
 * @param renewals     how many times the TTL has been extended
 * @param holder       the engineer or service account holding the lease
 * @param closedAt     when the lease left {@link LeaseState#ACTIVE}
 */
public record Lease(
        UUID id,
        UUID datasetId,
        String databaseName,
        String jdbcUrl,
        String username,
        String password,
        Instant issuedAt,
        Instant expiresAt,
        LeaseState state,
        int renewals,
        String holder,
        Instant closedAt) {

    /** Renewals allowed before a lease must be re-requested rather than extended. */
    public static final int MAX_RENEWALS = 6;

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public Duration remainingAt(Instant now) {
        Duration remaining = Duration.between(now, expiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public boolean canRenew() {
        return state == LeaseState.ACTIVE && renewals < MAX_RENEWALS;
    }

    /** The connection string with credentials stripped, safe to log and to return on read paths. */
    public String redactedJdbcUrl() {
        return jdbcUrl;
    }

    /** The full connection string, including credentials. Only non-null right after issue. */
    public String connectionString() {
        if (password == null) {
            return null;
        }
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "user=" + username + "&password=" + password;
    }

    /** A copy with the credential dropped, for anything that leaves the issuing call. */
    public Lease withoutSecret() {
        return new Lease(id, datasetId, databaseName, jdbcUrl, username, null,
                issuedAt, expiresAt, state, renewals, holder, closedAt);
    }

    public Lease withState(LeaseState newState, Instant at) {
        return new Lease(id, datasetId, databaseName, jdbcUrl, username, null,
                issuedAt, expiresAt, newState, renewals, holder, at);
    }

    public Lease renewedUntil(Instant newExpiry) {
        return new Lease(id, datasetId, databaseName, jdbcUrl, username, password,
                issuedAt, newExpiry, state, renewals + 1, holder, closedAt);
    }
}
