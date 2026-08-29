package com.helios.testforge.persistence;

import com.helios.testforge.domain.dataset.Dataset;
import com.helios.testforge.domain.dataset.DatasetSummary;
import com.helios.testforge.domain.job.JobStatus;
import com.helios.testforge.domain.plan.GenerationPlan;
import com.helios.testforge.domain.request.DatasetRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable dataset records in the control plane. */
@Repository
public class DatasetRepository {

    /** Columns the summary projection needs, deliberately excluding the JSONB payloads. */
    private static final String SUMMARY_COLUMNS = """
            id, name, requested_by, target_id, schema_name, status,
            total_rows, masked_columns, duration_ms, snapshot_uri, created_at, completed_at
            """;

    private final JdbcClient jdbc;
    private final Json json;

    public DatasetRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Dataset dataset) {
        jdbc.sql("""
                        INSERT INTO dataset (id, name, description, requested_by, target_id, schema_name,
                                             snapshot_id, seed, scale, request, plan, status,
                                             total_rows, masked_columns, created_at)
                        VALUES (:id, :name, :description, :requestedBy, :targetId, :schema,
                                :snapshotId, :seed, :scale, :request, :plan, :status,
                                :totalRows, :maskedColumns, :createdAt)
                        """)
                .param("id", dataset.id())
                .param("name", dataset.name())
                .param("description", dataset.description())
                .param("requestedBy", dataset.requestedBy())
                .param("targetId", dataset.targetId())
                .param("schema", dataset.schema())
                .param("snapshotId", dataset.snapshotId())
                .param("seed", dataset.seed())
                .param("scale", dataset.scale())
                .param("request", json.toJsonb(dataset.request()))
                .param("plan", json.toJsonb(dataset.plan()))
                .param("status", dataset.status().name())
                .param("totalRows", dataset.totalRows())
                .param("maskedColumns", dataset.maskedColumns())
                .param("createdAt", java.sql.Timestamp.from(dataset.createdAt()))
                .update();
    }

    /** Attaches the plan once planning completes, before any row is written. */
    public void attachPlan(UUID id, UUID snapshotId, GenerationPlan plan) {
        jdbc.sql("""
                        UPDATE dataset
                        SET plan = :plan, snapshot_id = :snapshotId, masked_columns = :maskedColumns
                        WHERE id = :id
                        """)
                .param("plan", json.toJsonb(plan))
                .param("snapshotId", snapshotId)
                .param("maskedColumns", plan.maskedColumns())
                .param("id", id)
                .update();
    }

    public void markRunning(UUID id) {
        jdbc.sql("UPDATE dataset SET status = 'RUNNING' WHERE id = :id AND status = 'PENDING'")
                .param("id", id)
                .update();
    }

    public void markSucceeded(UUID id, long totalRows, Duration duration, String snapshotUri) {
        jdbc.sql("""
                        UPDATE dataset
                        SET status = 'SUCCEEDED', total_rows = :totalRows, duration_ms = :durationMs,
                            snapshot_uri = :snapshotUri, completed_at = now(), error = NULL
                        WHERE id = :id
                        """)
                .param("totalRows", totalRows)
                .param("durationMs", duration == null ? null : duration.toMillis())
                .param("snapshotUri", snapshotUri)
                .param("id", id)
                .update();
    }

    public void markFailed(UUID id, String error, Duration duration) {
        jdbc.sql("""
                        UPDATE dataset
                        SET status = 'FAILED', error = :error, duration_ms = :durationMs, completed_at = now()
                        WHERE id = :id
                        """)
                .param("error", truncate(error))
                .param("durationMs", duration == null ? null : duration.toMillis())
                .param("id", id)
                .update();
    }

    public Optional<Dataset> findById(UUID id) {
        return jdbc.sql("SELECT * FROM dataset WHERE id = :id")
                .param("id", id)
                .query(this::mapDataset)
                .optional();
    }

    public List<DatasetSummary> findRecent(int limit) {
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM dataset ORDER BY created_at DESC LIMIT :limit")
                .param("limit", limit)
                .query(DatasetRepository::mapSummary)
                .list();
    }

    public List<DatasetSummary> findByRequester(String requestedBy, int limit) {
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS
                        + " FROM dataset WHERE requested_by = :who ORDER BY created_at DESC LIMIT :limit")
                .param("who", requestedBy)
                .param("limit", limit)
                .query(DatasetRepository::mapSummary)
                .list();
    }

    public List<DatasetSummary> findByTarget(String targetId, int limit) {
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS
                        + " FROM dataset WHERE target_id = :target ORDER BY created_at DESC LIMIT :limit")
                .param("target", targetId)
                .param("limit", limit)
                .query(DatasetRepository::mapSummary)
                .list();
    }

    /** Aggregate counters for the console's dashboard, in one query rather than four. */
    public DatasetStats stats() {
        return jdbc.sql("""
                        SELECT count(*)                                                    AS total,
                               count(*) FILTER (WHERE status = 'SUCCEEDED')                AS succeeded,
                               count(*) FILTER (WHERE status = 'FAILED')                   AS failed,
                               count(*) FILTER (WHERE status IN ('PENDING', 'RUNNING'))    AS in_flight,
                               COALESCE(sum(total_rows), 0)                                AS rows_generated,
                               COALESCE(avg(duration_ms) FILTER (WHERE status = 'SUCCEEDED'), 0) AS avg_duration_ms
                        FROM dataset
                        """)
                .query((ResultSet rs, int rowNum) -> new DatasetStats(
                        rs.getLong("total"),
                        rs.getLong("succeeded"),
                        rs.getLong("failed"),
                        rs.getLong("in_flight"),
                        rs.getLong("rows_generated"),
                        Math.round(rs.getDouble("avg_duration_ms"))))
                .single();
    }

    private Dataset mapDataset(ResultSet rs, int rowNum) throws SQLException {
        Long durationMs = rs.getObject("duration_ms", Long.class);
        return new Dataset(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("requested_by"),
                rs.getString("target_id"),
                rs.getString("schema_name"),
                rs.getObject("snapshot_id", UUID.class),
                rs.getLong("seed"),
                rs.getInt("scale"),
                json.fromJson(rs.getString("request"), DatasetRequest.class),
                json.fromJson(rs.getString("plan"), GenerationPlan.class),
                JobStatus.valueOf(rs.getString("status")),
                rs.getLong("total_rows"),
                rs.getInt("masked_columns"),
                durationMs == null ? null : Duration.ofMillis(durationMs),
                rs.getString("error"),
                rs.getString("snapshot_uri"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }

    private static DatasetSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new DatasetSummary(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("requested_by"),
                rs.getString("target_id"),
                rs.getString("schema_name"),
                JobStatus.valueOf(rs.getString("status")),
                rs.getLong("total_rows"),
                rs.getInt("masked_columns"),
                rs.getObject("duration_ms", Long.class),
                rs.getString("snapshot_uri"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }

    /** Keeps a pathological stack trace from bloating every row of the dataset table. */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 4_000 ? error.substring(0, 4_000) + "... (truncated)" : error;
    }

    /**
     * Dashboard counters.
     *
     * @param total          datasets ever requested
     * @param succeeded      datasets that completed
     * @param failed         datasets that failed
     * @param inFlight       datasets pending or running
     * @param rowsGenerated  rows written across every dataset
     * @param avgDurationMs  mean duration of a successful run
     */
    public record DatasetStats(
            long total, long succeeded, long failed, long inFlight, long rowsGenerated, long avgDurationMs) {
    }
}
