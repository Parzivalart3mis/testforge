package com.helios.testforge.api.dto;

import com.helios.testforge.domain.lease.Lease;
import com.helios.testforge.domain.lease.LeaseState;

import java.time.Instant;
import java.util.UUID;

/**
 * A lease as the console sees it.
 *
 * <p>{@code connectionString} is populated only on the response that issues or
 * renews the lease. Every listing and read returns it null, because the control
 * plane stores only a digest and genuinely cannot reconstruct it.
 */
public record LeaseDto(
        UUID id,
        UUID datasetId,
        String databaseName,
        String jdbcUrl,
        String username,
        String connectionString,
        String holder,
        LeaseState state,
        int renewals,
        int renewalsRemaining,
        Instant issuedAt,
        Instant expiresAt,
        long remainingSeconds,
        Instant closedAt) {

    /** A listing view, with no credential. */
    public static LeaseDto redacted(Lease lease) {
        return build(lease, null);
    }

    /** The issue-time view, carrying the connection string exactly once. */
    public static LeaseDto withCredentials(Lease lease) {
        return build(lease, lease.connectionString());
    }

    private static LeaseDto build(Lease lease, String connectionString) {
        return new LeaseDto(
                lease.id(),
                lease.datasetId(),
                lease.databaseName(),
                lease.redactedJdbcUrl(),
                lease.username(),
                connectionString,
                lease.holder(),
                lease.state(),
                lease.renewals(),
                Math.max(0, Lease.MAX_RENEWALS - lease.renewals()),
                lease.issuedAt(),
                lease.expiresAt(),
                lease.remainingAt(Instant.now()).toSeconds(),
                lease.closedAt());
    }
}
