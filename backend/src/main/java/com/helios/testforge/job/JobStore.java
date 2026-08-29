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
 * <p>Two implementations back this: DynamoDB in deployed environments, and an
 * in-memory map for local runs and tests. The interface is deliberately narrow —
 * put, get, list, and a small number of transitions — because that is the whole
 * access pattern, and keeping it that narrow is what lets a key-value store
 * serve it without compromise.
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
