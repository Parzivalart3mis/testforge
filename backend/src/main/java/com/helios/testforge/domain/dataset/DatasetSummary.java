package com.helios.testforge.domain.dataset;

import com.helios.testforge.domain.job.JobStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * A dataset without its request or plan payloads.
 *
 * <p>The console's list view shows dozens of these at once; loading each one's
 * full plan to render a row would move megabytes of JSONB to display a table of
 * names and row counts.
 */
public record DatasetSummary(
        UUID id,
        String name,
        String requestedBy,
        String targetId,
        String schema,
        JobStatus status,
        long totalRows,
        int maskedColumns,
        Long durationMs,
        String snapshotUri,
        Instant createdAt,
        Instant completedAt) {
}
