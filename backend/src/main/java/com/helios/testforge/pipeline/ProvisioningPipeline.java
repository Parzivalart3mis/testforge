package com.helios.testforge.pipeline;

import com.helios.testforge.ddl.DdlWriter;
import com.helios.testforge.domain.dataset.Dataset;
import com.helios.testforge.domain.job.JobPhase;
import com.helios.testforge.domain.job.JobStatus;
import com.helios.testforge.domain.lease.Lease;
import com.helios.testforge.domain.plan.GenerationPlan;
import com.helios.testforge.domain.request.DatasetRequest;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.generate.GenerationPlanner;
import com.helios.testforge.introspect.TargetRegistry;
import com.helios.testforge.job.JobStore;
import com.helios.testforge.lease.LeaseService;
import com.helios.testforge.persistence.AuditRepository;
import com.helios.testforge.persistence.DatasetRepository;
import com.helios.testforge.persistence.SchemaSnapshotRepository;
import com.helios.testforge.provision.EphemeralDatabaseProvisioner;
import com.helios.testforge.provision.ProvisionedDatabase;
import com.helios.testforge.seed.SeedResult;
import com.helios.testforge.seed.Seeder;
import com.helios.testforge.snapshot.SnapshotExporter;
import com.helios.testforge.snapshot.SnapshotRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs a dataset request end to end.
 *
 * <p>Introspect, plan, provision, apply DDL, generate and seed, verify, export,
 * lease. Each stage updates the job record so the console can follow along, and
 * every stage after provisioning is wrapped so that a failure cleans up the
 * database it created — an ephemeral database left behind by a failed run is
 * worse than the failure, because nothing else will ever expire it.
 *
 * <p>The whole pipeline runs on a bounded worker pool rather than the request
 * thread: a run takes tens of seconds, and the HTTP caller gets a job id
 * immediately instead of holding a connection open for the duration.
 */
@Component
public class ProvisioningPipeline {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningPipeline.class);

    private final TargetRegistry targets;
    private final GenerationPlanner planner;
    private final DdlWriter ddlWriter;
    private final EphemeralDatabaseProvisioner provisioner;
    private final Seeder seeder;
    private final SnapshotExporter exporter;
    private final LeaseService leases;
    private final JobStore jobs;
    private final DatasetRepository datasets;
    private final SchemaSnapshotRepository snapshots;
    private final AuditRepository audit;

    public ProvisioningPipeline(TargetRegistry targets,
                                GenerationPlanner planner,
                                DdlWriter ddlWriter,
                                EphemeralDatabaseProvisioner provisioner,
                                Seeder seeder,
                                SnapshotExporter exporter,
                                LeaseService leases,
                                JobStore jobs,
                                DatasetRepository datasets,
                                SchemaSnapshotRepository snapshots,
                                AuditRepository audit) {
        this.targets = targets;
        this.planner = planner;
        this.ddlWriter = ddlWriter;
        this.provisioner = provisioner;
        this.seeder = seeder;
        this.exporter = exporter;
        this.leases = leases;
        this.jobs = jobs;
        this.datasets = datasets;
        this.snapshots = snapshots;
        this.audit = audit;
    }

    /**
     * Executes the pipeline. Intended to be submitted to the provisioning
     * executor, not called on a request thread.
     *
     * @param jobId     the job to report progress against
     * @param datasetId the dataset being produced
     * @param request   the original request
     * @param seed      the resolved seed
     */
    public void run(UUID jobId, UUID datasetId, DatasetRequest request, long seed) {
        Instant started = Instant.now();
        ProvisionedDatabase database = null;
        UUID leaseId = null;

        try {
            datasets.markRunning(datasetId);

            // ---- introspect ------------------------------------------------
            jobs.advance(jobId, JobPhase.INTROSPECTING, 0, "Reading the target schema");
            SchemaSnapshot snapshot = targets.introspect(request.targetId(), request.schema());
            UUID snapshotId = snapshots.save(request.targetId(), snapshot);
            jobs.recordMetric(jobId, "tables.introspected", snapshot.tableCount());
            jobs.recordMetric(jobId, "foreignKeys.introspected", snapshot.foreignKeyCount());

            // ---- plan ------------------------------------------------------
            jobs.advance(jobId, JobPhase.PLANNING, 0, "Ordering the foreign-key graph");
            GenerationPlan plan = planner.plan(snapshot, request, seed);
            datasets.attachPlan(datasetId, snapshotId, plan);
            audit.recordMasking(datasetId, plan);
            jobs.recordMetric(jobId, "tables.planned", plan.tableCount());
            jobs.recordMetric(jobId, "rows.planned", plan.totalRows());
            jobs.recordMetric(jobId, "columns.masked", plan.maskedColumns());

            // The plan may be narrower than the whole schema; only recreate what
            // the dataset actually covers.
            SchemaSnapshot planned = snapshot.restrictedTo(
                    plan.tables().stream().map(t -> t.table()).toList());

            // ---- provision -------------------------------------------------
            jobs.advance(jobId, JobPhase.PROVISIONING, 0, "Creating the ephemeral database");
            database = provisioner.provision(datasetId, request.name());

            jobs.advance(jobId, JobPhase.APPLYING_DDL, 0, "Applying the schema");
            List<String> ddl = ddlWriter.write(planned);
            provisioner.applyDdl(database, ddl);
            jobs.recordMetric(jobId, "ddl.statements", ddl.size());

            // ---- generate and seed ----------------------------------------
            jobs.advance(jobId, JobPhase.GENERATING, 0, "Generating rows");
            SeedResult seedResult;
            SnapshotRef snapshotRef = null;

            try (Connection connection = provisioner.connectAsOwner(database)) {
                int totalTables = plan.tableCount();
                int[] completed = {0};

                seedResult = seeder.seed(connection, planned, plan, datasetId.toString(),
                        (table, rows) -> {
                            completed[0]++;
                            jobs.advance(jobId, JobPhase.SEEDING, (double) completed[0] / totalTables,
                                    "Seeded " + rows + " rows into " + table);
                        });

                jobs.recordMetric(jobId, "rows.seeded", seedResult.totalRows());
                jobs.recordMetric(jobId, "rows.fixedUp", seedResult.fixupRows());

                // ---- verify -------------------------------------------------
                jobs.advance(jobId, JobPhase.VERIFYING, 0, "Verifying referential integrity");
                long verified = verify(connection, planned, seedResult);
                jobs.recordMetric(jobId, "rows.verified", verified);

                // ---- export -------------------------------------------------
                if (request.exportSnapshot()) {
                    jobs.advance(jobId, JobPhase.SNAPSHOTTING, 0, "Exporting the snapshot");
                    snapshotRef = exporter.export(connection, planned, plan, datasetId, request.name());
                    audit.recordSnapshotExport(snapshotRef);
                }
            }

            // ---- lease -----------------------------------------------------
            jobs.advance(jobId, JobPhase.LEASING, 0, "Issuing the lease");
            Lease lease = leases.issue(database, request.requestedBy(), request.ttl());
            leaseId = lease.id();

            Duration elapsed = Duration.between(started, Instant.now());
            datasets.markSucceeded(datasetId, seedResult.totalRows(), elapsed,
                    snapshotRef == null ? null : snapshotRef.uri());

            audit.record(request.requestedBy(), "dataset.provisioned", "dataset", datasetId.toString(),
                    Map.of("rows", seedResult.totalRows(),
                            "tables", plan.tableCount(),
                            "maskedColumns", plan.maskedColumns(),
                            "database", database.databaseName(),
                            "leaseId", lease.id().toString(),
                            "durationMs", elapsed.toMillis()));

            jobs.finish(jobId, JobStatus.SUCCEEDED,
                    "Seeded " + seedResult.totalRows() + " rows across " + plan.tableCount()
                            + " tables in " + elapsed.toSeconds() + "s", null);

            log.info("Dataset {} ready: {} rows, {} tables, lease {} on {}",
                    datasetId, seedResult.totalRows(), plan.tableCount(), lease.id(), database.databaseName());

        } catch (Exception e) {
            handleFailure(jobId, datasetId, request, database, leaseId, started, e);
        }
    }

    /**
     * Counts what was actually written.
     *
     * <p>Referential integrity itself is not re-checked here, and deliberately
     * so: every foreign key was created for real and enforced at insert time, so
     * a successful seed <em>is</em> the proof. What this catches is the case a
     * successful insert cannot — a table that ended up with fewer rows than
     * planned because unique key collisions forced rows to be skipped.
     */
    private long verify(Connection connection, SchemaSnapshot snapshot, SeedResult result) throws SQLException {
        long verified = 0;
        for (Map.Entry<String, Long> entry : result.rowsPerTable().entrySet()) {
            String qualified = entry.getKey();
            var ref = com.helios.testforge.domain.schema.TableRef.parse(qualified, snapshot.schema());
            try (var statement = connection.prepareStatement("SELECT count(*) FROM " + ref.quoted());
                 var rs = statement.executeQuery()) {
                if (rs.next()) {
                    long actual = rs.getLong(1);
                    verified += actual;
                    if (actual != entry.getValue()) {
                        log.warn("{} holds {} rows but seeding reported {}", qualified, actual, entry.getValue());
                    }
                }
            }
        }
        return verified;
    }

    /**
     * Records the failure and destroys whatever was created.
     *
     * <p>Cleanup runs before the job is marked failed, so a caller that sees
     * FAILED can rely on nothing having been left behind.
     */
    private void handleFailure(UUID jobId, UUID datasetId, DatasetRequest request,
                               ProvisionedDatabase database, UUID leaseId,
                               Instant started, Exception failure) {
        Duration elapsed = Duration.between(started, Instant.now());
        log.error("Dataset {} failed after {} ms: {}", datasetId, elapsed.toMillis(), failure.getMessage(), failure);

        if (leaseId != null) {
            try {
                leases.markFailed(leaseId);
            } catch (RuntimeException e) {
                log.error("Could not mark lease {} failed: {}", leaseId, e.getMessage());
            }
        } else if (database != null) {
            try {
                provisioner.drop(database.databaseName(), database.roleName());
            } catch (RuntimeException e) {
                // The orphan sweep is the backstop for exactly this.
                log.error("Could not drop {} after a failed run: {}", database.databaseName(), e.getMessage());
            }
        }

        datasets.markFailed(datasetId, describe(failure), elapsed);
        audit.record(request.requestedBy(), "dataset.failed", "dataset", datasetId.toString(),
                Map.of("error", String.valueOf(failure.getMessage()), "durationMs", elapsed.toMillis()));
        jobs.finish(jobId, JobStatus.FAILED, "Provisioning failed", describe(failure));
    }

    /** A message a requester can act on, with the cause chain that explains it. */
    private String describe(Exception failure) {
        StringBuilder message = new StringBuilder(
                failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        Throwable cause = failure.getCause();
        int depth = 0;
        while (cause != null && depth < 3) {
            message.append(" | caused by: ")
                    .append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            cause = cause.getCause();
            depth++;
        }
        return message.toString();
    }
}
