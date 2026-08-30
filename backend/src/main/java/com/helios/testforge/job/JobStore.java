package com.helios.testforge.job;

import com.helios.testforge.domain.job.Job;
import com.helios.testforge.domain.job.JobPhase;
import com.helios.testforge.domain.job.JobStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where provisioning job state lives.
 *
 * <p>Job records are written on every phase transition and polled by the console
 * a couple of times a second while a run is in flight. That is progress data,
 * not a system of record: the durable answer to "what was requested and what
 * was masked" is in the control-plane database, and a job record is the live
 * view of a run that is currently happening.
 *
 * <p>So it is held in memory, bounded, and expires. The interface stays narrow —
 * put, get, list, and a few transitions — which is both the whole access pattern
 * and what would let a durable key-value store slot in behind it unchanged if a
 * deployment ever ran more than one instance.
 */
public interface JobStore {

    /** Writes a job record, overwriting any previous version. */
    void save(Job job);

    Optional<Job> find(UUID id);

    /** The most recent jobs, newest first. */
    List<Job> recent(int limit);

    /** Recent jobs for one requester. */
    List<Job> recentFor(String requestedBy, int limit);

    /**
     * Advances a job to a new phase.
     *
     * @param fraction progress within the phase, 0 to 1
     * @return the updated job, or empty when the job no longer exists
     */
    Optional<Job> advance(UUID id, JobPhase phase, double fraction, String message);

    /** Records a counter gathered during the run, e.g. rows written for one table. */
    Optional<Job> recordMetric(UUID id, String name, long value);

    /** Moves a job to a terminal status. */
    Optional<Job> finish(UUID id, JobStatus status, String message, String error);

    /** Job counts by status, for the console's dashboard. */
    Map<JobStatus, Long> countsByStatus();

    /** Which backend is in use, surfaced on the health endpoint. */
    String backendName();
}
