package com.helios.testforge.api.dto;

import com.helios.testforge.domain.job.Job;
import com.helios.testforge.domain.job.JobPhase;
import com.helios.testforge.domain.job.JobStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A job as the console sees it. */
public record JobDto(
        UUID id,
        UUID datasetId,
        String requestedBy,
        JobStatus status,
        JobPhase phase,
        String phaseLabel,
        int percent,
        String message,
        String error,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        long elapsedMs,
        Map<String, Long> metrics,
        List<EventDto> events) {

    public static JobDto from(Job job) {
        return new JobDto(
                job.id(),
                job.datasetId(),
                job.requestedBy(),
                job.status(),
                job.phase(),
                job.phase().label(),
                job.percent(),
                job.message(),
                job.error(),
                job.createdAt(),
                job.startedAt(),
                job.finishedAt(),
                job.elapsed(Instant.now()).toMillis(),
                job.metrics(),
                job.events().stream()
                        .map(event -> new EventDto(event.phase(), event.phase().label(), event.message(), event.at()))
                        .toList());
    }

    public record EventDto(JobPhase phase, String label, String message, Instant at) {
    }
}
