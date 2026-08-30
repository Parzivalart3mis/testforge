package com.helios.testforge.job;

import com.helios.testforge.domain.job.Job;
import com.helios.testforge.domain.job.JobPhase;
import com.helios.testforge.domain.job.JobStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Job state in memory.
 *
 * <p>Bounded rather than unbounded: a long-lived instance would otherwise
 * accumulate every job it ever ran. Eviction takes the oldest finished jobs
 * first and never touches a running one, so a busy period cannot lose the
 * progress of a run someone is currently watching.
 */
@Component
public class InMemoryJobStore implements JobStore {

    /** Jobs retained before the oldest terminal ones are evicted. */
    private static final int CAPACITY = 500;

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

    @Override
    public void save(Job job) {
        jobs.put(job.id(), job);
        evictIfNeeded();
    }

    @Override
    public Optional<Job> find(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public List<Job> recent(int limit) {
        return jobs.values().stream()
                .sorted(Comparator.comparing(Job::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<Job> recentFor(String requestedBy, int limit) {
        return jobs.values().stream()
                .filter(job -> job.requestedBy().equals(requestedBy))
                .sorted(Comparator.comparing(Job::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<Job> advance(UUID id, JobPhase phase, double fraction, String message) {
        return Optional.ofNullable(jobs.computeIfPresent(id, (key, job) -> JobUpdates.advance(job, phase, fraction, message)));
    }

    @Override
    public Optional<Job> recordMetric(UUID id, String name, long value) {
        return Optional.ofNullable(jobs.computeIfPresent(id, (key, job) -> JobUpdates.withMetric(job, name, value)));
    }

    @Override
    public Optional<Job> finish(UUID id, JobStatus status, String message, String error) {
        return Optional.ofNullable(jobs.computeIfPresent(id, (key, job) -> JobUpdates.finish(job, status, message, error)));
    }

    @Override
    public Map<JobStatus, Long> countsByStatus() {
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        jobs.values().stream()
                .collect(Collectors.groupingBy(Job::status, Collectors.counting()))
                .forEach(counts::put);
        return counts;
    }

    @Override
    public String backendName() {
        return "memory";
    }

    /** Drops the oldest finished jobs once the map is over capacity. Running jobs are never evicted. */
    private void evictIfNeeded() {
        if (jobs.size() <= CAPACITY) {
            return;
        }
        List<Job> terminal = new ArrayList<>(jobs.values().stream()
                .filter(Job::isTerminal)
                .sorted(Comparator.comparing(Job::createdAt))
                .toList());
        int toRemove = jobs.size() - CAPACITY;
        for (int i = 0; i < Math.min(toRemove, terminal.size()); i++) {
            jobs.remove(terminal.get(i).id());
        }
    }

    /** Test seam: forget everything. */
    public void clear() {
        jobs.clear();
    }

    /**
     * The state transitions, kept separate from the storage so a different
     * store would inherit identical semantics rather than reimplementing them.
     */
    static final class JobUpdates {

        private JobUpdates() {
        }

        static Job advance(Job job, JobPhase phase, double fraction, String message) {
            Instant now = Instant.now();
            List<Job.JobEvent> events = new ArrayList<>(job.events());
            // Only log a transition when the phase actually changes; progress
            // within a phase updates the percentage without a new event.
            if (job.phase() != phase) {
                events.add(new Job.JobEvent(phase, message, now));
            }
            return new Job(
                    job.id(), job.datasetId(), job.requestedBy(),
                    JobStatus.RUNNING, phase, phase.overallPercent(fraction), message,
                    job.createdAt(), job.startedAt() == null ? now : job.startedAt(),
                    job.finishedAt(), job.error(), job.metrics(), events, job.expiresAt());
        }

        static Job withMetric(Job job, String name, long value) {
            Map<String, Long> metrics = new LinkedHashMap<>(job.metrics());
            metrics.put(name, value);
            return new Job(
                    job.id(), job.datasetId(), job.requestedBy(), job.status(), job.phase(),
                    job.percent(), job.message(), job.createdAt(), job.startedAt(),
                    job.finishedAt(), job.error(), metrics, job.events(), job.expiresAt());
        }

        static Job finish(Job job, JobStatus status, String message, String error) {
            Instant now = Instant.now();
            JobPhase phase = status == JobStatus.SUCCEEDED ? JobPhase.DONE : job.phase();
            List<Job.JobEvent> events = new ArrayList<>(job.events());
            events.add(new Job.JobEvent(phase, message, now));

            return new Job(
                    job.id(), job.datasetId(), job.requestedBy(), status, phase,
                    status == JobStatus.SUCCEEDED ? 100 : job.percent(),
                    message, job.createdAt(), job.startedAt(), now, error,
                    job.metrics(), events, now.plus(Job.RETENTION));
        }
    }
}
