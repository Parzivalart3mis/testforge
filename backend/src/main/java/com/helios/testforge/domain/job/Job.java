package com.helios.testforge.domain.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The state of one dataset provisioning run.
 *
 * <p>Jobs live in DynamoDB rather than the control-plane database. They are
 * written on every phase transition and polled by the console, which is a
 * high-frequency, single-key, short-lived access pattern — exactly what a
 * key-value store with a TTL attribute is for, and exactly what we do not want
 * competing with the metadata database for connections.
 *
 * @param id           job identity, surfaced to the requester immediately
 * @param datasetId    the dataset this run produces
 * @param requestedBy  who asked for it
 * @param status       coarse state
 * @param phase        fine-grained pipeline stage
 * @param percent      overall completion, 0-100
 * @param message      current human-readable status line
 * @param createdAt    when the job was accepted
 * @param startedAt    when a worker picked it up
 * @param finishedAt   when it reached a terminal status
 * @param error        failure detail, when {@code status} is FAILED
 * @param metrics      counters gathered during the run, e.g. rows per table
 * @param events       append-only phase transition log
 * @param expiresAt    DynamoDB TTL attribute — job records self-delete after retention
 */
public record Job(
        UUID id,
        UUID datasetId,
        String requestedBy,
        JobStatus status,
        JobPhase phase,
        int percent,
        String message,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String error,
        Map<String, Long> metrics,
        List<JobEvent> events,
        Instant expiresAt) {

    /** How long a finished job record is retained before DynamoDB's TTL reaps it. */
    public static final Duration RETENTION = Duration.ofDays(14);

    public Job {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        events = events == null ? List.of() : List.copyOf(events);
    }

    public static Job accepted(UUID id, UUID datasetId, String requestedBy, Instant now) {
        return new Job(id, datasetId, requestedBy, JobStatus.PENDING, JobPhase.QUEUED, 0,
                JobPhase.QUEUED.label(), now, null, null, null, Map.of(),
                List.of(new JobEvent(JobPhase.QUEUED, "Job accepted", now)),
                now.plus(RETENTION));
    }

    public Duration elapsed(Instant now) {
        Instant from = startedAt != null ? startedAt : createdAt;
        Instant to = finishedAt != null ? finishedAt : now;
        return Duration.between(from, to);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    /** One phase transition, kept so the console can show what the run actually did. */
    public record JobEvent(JobPhase phase, String message, Instant at) {
    }
}
