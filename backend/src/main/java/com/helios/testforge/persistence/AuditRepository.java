package com.helios.testforge.persistence;

import com.helios.testforge.domain.plan.ColumnPlan;
import com.helios.testforge.domain.plan.GenerationPlan;
import com.helios.testforge.domain.plan.TablePlan;
import com.helios.testforge.snapshot.SnapshotRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The audit trail.
 *
 * <p>A platform that reads production-shaped schemas and hands out live database
 * credentials has to be able to answer, after the fact, who asked for what and
 * which columns were masked on the way. Both questions are indexed queries here
 * rather than a replay of application logs.
 */
@Repository
public class AuditRepository {

    private final JdbcClient jdbc;
    private final Json json;

    public AuditRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Records an action against a subject. */
    public void record(String actor, String action, String subjectType, String subjectId,
                       Map<String, Object> detail) {
        jdbc.sql("""
                        INSERT INTO audit_event (actor, action, subject_type, subject_id, detail)
                        VALUES (:actor, :action, :subjectType, :subjectId, :detail)
                        """)
                .param("actor", actor)
                .param("action", action)
                .param("subjectType", subjectType)
                .param("subjectId", subjectId)
                .param("detail", json.toJsonb(detail == null ? Map.of() : detail))
                .update();
    }

    public List<AuditEvent> forSubject(String subjectType, String subjectId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM audit_event
                        WHERE subject_type = :type AND subject_id = :id
                        ORDER BY occurred_at DESC LIMIT :limit
                        """)
                .param("type", subjectType)
                .param("id", subjectId)
                .param("limit", limit)
                .query(AuditRepository::mapEvent)
                .list();
    }

    public List<AuditEvent> recent(int limit) {
        return jdbc.sql("SELECT * FROM audit_event ORDER BY occurred_at DESC LIMIT :limit")
                .param("limit", limit)
                .query(AuditRepository::mapEvent)
                .list();
    }

    /**
     * Writes the masking decisions for a dataset, one row per masked column.
     *
     * <p>Written at plan time rather than after seeding, so the record exists
     * even if the run fails halfway — the question "was this column ever going
     * to be seeded in the clear?" is answerable regardless of the outcome.
     */
    public void recordMasking(UUID datasetId, GenerationPlan plan) {
        for (TablePlan tablePlan : plan.tables()) {
            for (ColumnPlan column : tablePlan.columns()) {
                if (!column.isMasked()) {
                    continue;
                }
                jdbc.sql("""
                                INSERT INTO masking_record
                                    (dataset_id, table_name, column_name, data_class, strategy, rule_source)
                                VALUES (:datasetId, :table, :column, :dataClass, :strategy, :source)
                                ON CONFLICT (dataset_id, table_name, column_name) DO UPDATE
                                SET strategy = EXCLUDED.strategy, rule_source = EXCLUDED.rule_source
                                """)
                        .param("datasetId", datasetId)
                        .param("table", tablePlan.table().qualified())
                        .param("column", column.column())
                        .param("dataClass", column.dataClass().name())
                        .param("strategy", column.mask().name())
                        .param("source", column.maskSource())
                        .update();
            }
        }
    }

    public List<MaskingRecord> maskingFor(UUID datasetId) {
        return jdbc.sql("""
                        SELECT * FROM masking_record WHERE dataset_id = :id
                        ORDER BY table_name, column_name
                        """)
                .param("id", datasetId)
                .query(AuditRepository::mapMasking)
                .list();
    }

    /**
     * Sensitive columns that were <em>not</em> masked, across every dataset.
     *
     * <p>This is the query a compliance review actually runs. It returns nothing
     * in a healthy system, and each row it does return names a dataset where an
     * explicit rule overrode the sensitivity default.
     */
    public List<MaskingRecord> unmaskedSensitiveColumns(int limit) {
        return jdbc.sql("""
                        SELECT * FROM masking_record
                        WHERE strategy = 'PRESERVE'
                        ORDER BY dataset_id, table_name, column_name
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query(AuditRepository::mapMasking)
                .list();
    }

    public void recordSnapshotExport(SnapshotRef ref) {
        jdbc.sql("""
                        INSERT INTO snapshot_export
                            (id, dataset_id, uri, byte_size, row_count, table_count, checksum)
                        VALUES (:id, :datasetId, :uri, :bytes, :rows, :tables, :checksum)
                        """)
                .param("id", UUID.randomUUID())
                .param("datasetId", ref.datasetId())
                .param("uri", ref.uri())
                .param("bytes", ref.byteSize())
                .param("rows", ref.rowCount())
                .param("tables", ref.tableCount())
                .param("checksum", ref.checksum())
                .update();
    }

    public List<SnapshotRef> snapshotsFor(UUID datasetId) {
        return jdbc.sql("""
                        SELECT dataset_id, uri, byte_size, row_count, table_count, checksum, created_at
                        FROM snapshot_export WHERE dataset_id = :id ORDER BY created_at DESC
                        """)
                .param("id", datasetId)
                .query((ResultSet rs, int rowNum) -> new SnapshotRef(
                        rs.getObject("dataset_id", UUID.class),
                        rs.getString("uri"),
                        rs.getLong("byte_size"),
                        rs.getString("checksum"),
                        rs.getLong("row_count"),
                        rs.getInt("table_count"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private static AuditEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new AuditEvent(
                rs.getLong("id"),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("subject_type"),
                rs.getString("subject_id"),
                rs.getString("detail"),
                rs.getTimestamp("occurred_at").toInstant());
    }

    private static MaskingRecord mapMasking(ResultSet rs, int rowNum) throws SQLException {
        return new MaskingRecord(
                rs.getObject("dataset_id", UUID.class),
                rs.getString("table_name"),
                rs.getString("column_name"),
                rs.getString("data_class"),
                rs.getString("strategy"),
                rs.getString("rule_source"));
    }

    /** One recorded action. {@code detail} stays raw JSON; nothing queries inside it. */
    public record AuditEvent(
            long id, String actor, String action, String subjectType,
            String subjectId, String detail, Instant occurredAt) {
    }

    /** How one column of one dataset was masked. */
    public record MaskingRecord(
            UUID datasetId, String table, String column,
            String dataClass, String strategy, String ruleSource) {
    }
}
