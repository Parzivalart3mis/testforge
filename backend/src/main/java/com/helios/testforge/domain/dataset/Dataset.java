package com.helios.testforge.domain.dataset;

import com.helios.testforge.domain.job.JobStatus;
import com.helios.testforge.domain.plan.GenerationPlan;
import com.helios.testforge.domain.request.DatasetRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The durable record of one dataset.
 *
 * <p>The request and the plan are both stored verbatim. Together with the seed
 * and the schema fingerprint they are the reproducibility record: given these,
 * the dataset can be rebuilt value-for-value, which is what makes a bug found
 * against a dataset reproducible weeks after its lease expired.
 *
 * @param id             dataset identity
 * @param name           human label
 * @param description    free-text note from the requester
 * @param requestedBy    who asked for it
 * @param targetId       the registered target whose schema was modelled
 * @param schema         the schema within that target
 * @param snapshotId     the schema snapshot it was planned against
 * @param seed           the seed every value derives from
 * @param scale          the requested baseline row count
 * @param request        the original request, stored verbatim
 * @param plan           the generation plan, stored verbatim; null until planning completes
 * @param status         current status, mirroring the job
 * @param totalRows      rows actually written
 * @param maskedColumns  columns that were masked
 * @param duration       how long the run took
 * @param error          failure detail, when the run failed
 * @param snapshotUri    where the exported bundle lives, when one was written
 * @param createdAt      when the request was accepted
 * @param completedAt    when the run finished
 */
public record Dataset(
        UUID id,
        String name,
        String description,
        String requestedBy,
        String targetId,
        String schema,
        UUID snapshotId,
        long seed,
        int scale,
        DatasetRequest request,
        GenerationPlan plan,
        JobStatus status,
        long totalRows,
        int maskedColumns,
        Duration duration,
        String error,
        String snapshotUri,
        Instant createdAt,
        Instant completedAt) {

    public boolean isComplete() {
        return status == JobStatus.SUCCEEDED;
    }

    /** Whether the dataset can be regenerated exactly as it was. */
    public boolean isReproducible() {
        return plan != null && seed != 0;
    }
}
