package com.helios.testforge.pipeline;

import com.helios.testforge.config.TestForgeProperties;
import com.helios.testforge.domain.dataset.Dataset;
import com.helios.testforge.domain.dataset.DatasetSummary;
import com.helios.testforge.domain.job.Job;
import com.helios.testforge.domain.plan.GenerationPlan;
import com.helios.testforge.domain.request.DatasetRequest;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.generate.GenerationPlanner;
import com.helios.testforge.introspect.TargetRegistry;
import com.helios.testforge.job.JobStore;
import com.helios.testforge.persistence.AuditRepository;
import com.helios.testforge.persistence.DatasetRepository;
import com.helios.testforge.persistence.SchemaSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * The application-facing entry point for datasets.
 *
 * <p>Accepting a request is fast and synchronous — validate, persist, return a
 * job id. The work itself is handed to the provisioning pool. A caller that
 * wants to wait polls the job; a caller that does not can walk away and come
 * back to the console.
 */
@Service
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    private static final SecureRandom SEED_SOURCE = new SecureRandom();

    private final ProvisioningPipeline pipeline;
    private final ExecutorService executor;
    private final DatasetRepository datasets;
    private final SchemaSnapshotRepository snapshots;
    private final AuditRepository audit;
    private final JobStore jobs;
    private final TargetRegistry targets;
    private final GenerationPlanner planner;
    private final TestForgeProperties properties;

    public DatasetService(ProvisioningPipeline pipeline,
                          @Qualifier("provisioningExecutor") ExecutorService executor,
                          DatasetRepository datasets,
                          SchemaSnapshotRepository snapshots,
                          AuditRepository audit,
                          JobStore jobs,
                          TargetRegistry targets,
                          GenerationPlanner planner,
                          TestForgeProperties properties) {
        this.pipeline = pipeline;
        this.executor = executor;
        this.datasets = datasets;
        this.snapshots = snapshots;
        this.audit = audit;
        this.jobs = jobs;
        this.targets = targets;
        this.planner = planner;
        this.properties = properties;
    }

    /**
     * Accepts a dataset request and starts provisioning.
     *
     * @return the accepted job, whose id the caller polls for progress
     */
    public Job request(DatasetRequest request) {
        targets.require(request.targetId());

        UUID datasetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        long seed = request.hasExplicitSeed() ? request.seed() : SEED_SOURCE.nextLong();
        Instant now = Instant.now();

        Dataset dataset = new Dataset(
                datasetId, request.name(), request.description(), request.requestedBy(),
                request.targetId(), request.schema(), null, seed, request.scale(),
                request, null, com.helios.testforge.domain.job.JobStatus.PENDING,
                0, 0, null, null, null, now, null);

        // Persisted before the job is queued, so a worker picking it up
        // immediately always finds the dataset row it needs to update.
        datasets.insert(dataset);

        Job job = Job.accepted(jobId, datasetId, request.requestedBy(), now);
        jobs.save(job);

        audit.record(request.requestedBy(), "dataset.requested", "dataset", datasetId.toString(),
                Map.of("target", request.targetId(), "schema", request.schema(),
                        "scale", request.scale(), "seed", seed));

        executor.submit(() -> pipeline.run(jobId, datasetId, request, seed));
        log.info("Accepted dataset request {} as job {} (seed {})", datasetId, jobId, seed);
        return job;
    }

    /**
     * Builds a plan without provisioning anything.
     *
     * <p>This is the dry run: it introspects and plans, so a requester can see
     * the table order, the row counts and every masking decision, and then
     * decide whether to submit. Nothing is created and nothing is charged.
     */
    public GenerationPlan preview(DatasetRequest request) {
        SchemaSnapshot snapshot = targets.introspect(request.targetId(), request.schema());
        snapshots.save(request.targetId(), snapshot);
        long seed = request.hasExplicitSeed() ? request.seed() : SEED_SOURCE.nextLong();
        return planner.plan(snapshot, request, seed);
    }

    /**
     * Requests a dataset identical to an earlier one.
     *
     * <p>Replays the stored request with the stored seed, which reproduces the
     * dataset value-for-value provided the schema has not drifted. If it has,
     * the new run's fingerprint differs and the plan says so.
     */
    public Job regenerate(UUID datasetId, String requestedBy) {
        Dataset original = datasets.findById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("no dataset " + datasetId));
        if (!original.isReproducible()) {
            throw new IllegalStateException("dataset " + datasetId
                    + " has no stored plan or seed, so it cannot be reproduced");
        }

        DatasetRequest replay = new DatasetRequest(
                original.request().name() + " (regenerated)",
                "Regenerated from dataset " + datasetId,
                requestedBy,
                original.request().targetId(),
                original.request().schema(),
                original.request().includeTables(),
                original.request().excludeTables(),
                original.request().scale(),
                original.request().rowOverrides(),
                original.seed(),
                original.request().ttl(),
                original.request().masking(),
                original.request().exportSnapshot());

        audit.record(requestedBy, "dataset.regenerated", "dataset", datasetId.toString(),
                Map.of("seed", original.seed()));
        return request(replay);
    }

    public Optional<Dataset> find(UUID id) {
        return datasets.findById(id);
    }

    public List<DatasetSummary> recent(int limit) {
        return datasets.findRecent(clampLimit(limit));
    }

    public List<DatasetSummary> forRequester(String requestedBy, int limit) {
        return datasets.findByRequester(requestedBy, clampLimit(limit));
    }

    public List<DatasetSummary> forTarget(String targetId, int limit) {
        return datasets.findByTarget(targetId, clampLimit(limit));
    }

    public DatasetRepository.DatasetStats stats() {
        return datasets.stats();
    }

    public List<AuditRepository.MaskingRecord> maskingFor(UUID datasetId) {
        return audit.maskingFor(datasetId);
    }

    /** The default scale, surfaced so the console's form matches the server's behaviour. */
    public int defaultScale() {
        return properties.generation().defaultScale();
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }
}
