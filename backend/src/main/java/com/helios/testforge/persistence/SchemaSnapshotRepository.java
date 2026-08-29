package com.helios.testforge.persistence;

import com.helios.testforge.domain.schema.SchemaSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stored schema snapshots.
 *
 * <p>Snapshots are content-addressed by fingerprint. Re-introspecting an
 * unchanged schema finds the existing row instead of inserting a near-duplicate,
 * which keeps the table proportional to the number of times a schema actually
 * changed rather than the number of datasets ever requested against it.
 */
@Repository
public class SchemaSnapshotRepository {

    private final JdbcClient jdbc;
    private final Json json;

    public SchemaSnapshotRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Stores a snapshot, or returns the id of the identical one already stored.
     *
     * <p>The insert races with any concurrent introspection of the same schema,
     * so it relies on the unique index over (target, schema, fingerprint) rather
     * than a check-then-insert: {@code ON CONFLICT DO NOTHING} makes the loser
     * of the race fall through to the lookup instead of failing.
     *
     * @return the id of the stored snapshot
     */
    public UUID save(String targetId, SchemaSnapshot snapshot) {
        Optional<UUID> existing = findIdByFingerprint(targetId, snapshot.schema(), snapshot.fingerprint());
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO schema_snapshot (id, target_id, database_name, schema_name, fingerprint,
                                                     table_count, column_count, foreign_key_count,
                                                     payload, captured_at)
                        VALUES (:id, :targetId, :database, :schema, :fingerprint,
                                :tables, :columns, :foreignKeys, :payload, :capturedAt)
                        ON CONFLICT (target_id, schema_name, fingerprint) DO NOTHING
                        """)
                .param("id", id)
                .param("targetId", targetId)
                .param("database", snapshot.database())
                .param("schema", snapshot.schema())
                .param("fingerprint", snapshot.fingerprint())
                .param("tables", snapshot.tableCount())
                .param("columns", (int) snapshot.columnCount())
                .param("foreignKeys", (int) snapshot.foreignKeyCount())
                .param("payload", json.toJsonb(snapshot))
                .param("capturedAt", java.sql.Timestamp.from(snapshot.capturedAt()))
                .update();

        return findIdByFingerprint(targetId, snapshot.schema(), snapshot.fingerprint()).orElse(id);
    }

    public Optional<UUID> findIdByFingerprint(String targetId, String schema, String fingerprint) {
        return jdbc.sql("""
                        SELECT id FROM schema_snapshot
                        WHERE target_id = :targetId AND schema_name = :schema AND fingerprint = :fingerprint
                        """)
                .param("targetId", targetId)
                .param("schema", schema)
                .param("fingerprint", fingerprint)
                .query(UUID.class)
                .optional();
    }

    public Optional<SchemaSnapshot> findById(UUID id) {
        return jdbc.sql("SELECT payload FROM schema_snapshot WHERE id = :id")
                .param("id", id)
                .query((ResultSet rs, int rowNum) -> json.fromJson(rs.getString("payload"), SchemaSnapshot.class))
                .optional();
    }

    /** The most recent snapshot for a target's schema, used when a request does not pin one. */
    public Optional<SchemaSnapshot> findLatest(String targetId, String schema) {
        return jdbc.sql("""
                        SELECT payload FROM schema_snapshot
                        WHERE target_id = :targetId AND schema_name = :schema
                        ORDER BY captured_at DESC LIMIT 1
                        """)
                .param("targetId", targetId)
                .param("schema", schema)
                .query((ResultSet rs, int rowNum) -> json.fromJson(rs.getString("payload"), SchemaSnapshot.class))
                .optional();
    }

    /** Snapshot history for a target, newest first, for the console's drift view. */
    public List<SnapshotHeader> history(String targetId, int limit) {
        return jdbc.sql("""
                        SELECT id, target_id, database_name, schema_name, fingerprint,
                               table_count, column_count, foreign_key_count, captured_at
                        FROM schema_snapshot
                        WHERE target_id = :targetId
                        ORDER BY captured_at DESC
                        LIMIT :limit
                        """)
                .param("targetId", targetId)
                .param("limit", limit)
                .query(SchemaSnapshotRepository::mapHeader)
                .list();
    }

    private static SnapshotHeader mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new SnapshotHeader(
                rs.getObject("id", UUID.class),
                rs.getString("target_id"),
                rs.getString("database_name"),
                rs.getString("schema_name"),
                rs.getString("fingerprint"),
                rs.getInt("table_count"),
                rs.getInt("column_count"),
                rs.getInt("foreign_key_count"),
                rs.getTimestamp("captured_at").toInstant());
    }

    /** A snapshot's metadata without its payload, for listing. */
    public record SnapshotHeader(
            UUID id,
            String targetId,
            String database,
            String schema,
            String fingerprint,
            int tableCount,
            int columnCount,
            int foreignKeyCount,
            Instant capturedAt) {
    }
}
