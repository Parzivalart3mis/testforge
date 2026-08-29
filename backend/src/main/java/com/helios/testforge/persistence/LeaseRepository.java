package com.helios.testforge.persistence;

import com.helios.testforge.domain.lease.Lease;
import com.helios.testforge.domain.lease.LeaseState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable lease records in the control plane. */
@Repository
public class LeaseRepository {

    private final JdbcClient jdbc;

    public LeaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a lease. The plaintext password is never written; only a digest,
     * so a control-plane dump cannot be used to connect to a live database.
     */
    public void insert(Lease lease, String passwordDigest) {
        jdbc.sql("""
                        INSERT INTO lease (id, dataset_id, database_name, jdbc_url, username,
                                           password_digest, holder, state, renewals, issued_at, expires_at)
                        VALUES (:id, :datasetId, :databaseName, :jdbcUrl, :username,
                                :digest, :holder, :state, :renewals, :issuedAt, :expiresAt)
                        """)
                .param("id", lease.id())
                .param("datasetId", lease.datasetId())
                .param("databaseName", lease.databaseName())
                .param("jdbcUrl", lease.jdbcUrl())
                .param("username", lease.username())
                .param("digest", passwordDigest)
                .param("holder", lease.holder())
                .param("state", lease.state().name())
                .param("renewals", lease.renewals())
                .param("issuedAt", java.sql.Timestamp.from(lease.issuedAt()))
                .param("expiresAt", java.sql.Timestamp.from(lease.expiresAt()))
                .update();
    }

    public Optional<Lease> findById(UUID id) {
        return jdbc.sql("SELECT * FROM lease WHERE id = :id")
                .param("id", id)
                .query(LeaseRepository::mapLease)
                .optional();
    }

    public Optional<Lease> findByDataset(UUID datasetId) {
        return jdbc.sql("SELECT * FROM lease WHERE dataset_id = :datasetId ORDER BY issued_at DESC LIMIT 1")
                .param("datasetId", datasetId)
                .query(LeaseRepository::mapLease)
                .optional();
    }

    public List<Lease> findAll(int limit) {
        return jdbc.sql("SELECT * FROM lease ORDER BY issued_at DESC LIMIT :limit")
                .param("limit", limit)
                .query(LeaseRepository::mapLease)
                .list();
    }

    public List<Lease> findByState(LeaseState state, int limit) {
        return jdbc.sql("SELECT * FROM lease WHERE state = :state ORDER BY expires_at ASC LIMIT :limit")
                .param("state", state.name())
                .param("limit", limit)
                .query(LeaseRepository::mapLease)
                .list();
    }

    public List<Lease> findByHolder(String holder, int limit) {
        return jdbc.sql("SELECT * FROM lease WHERE holder = :holder ORDER BY issued_at DESC LIMIT :limit")
                .param("holder", holder)
                .param("limit", limit)
                .query(LeaseRepository::mapLease)
                .list();
    }

    /**
     * Leases the reaper should act on: still ACTIVE, and past their expiry plus
     * the grace period. Hits the partial index on (expires_at) WHERE state = ACTIVE.
     */
    public List<Lease> findReapable(Instant cutoff, int limit) {
        return jdbc.sql("""
                        SELECT * FROM lease
                        WHERE state = 'ACTIVE' AND expires_at < :cutoff
                        ORDER BY expires_at ASC
                        LIMIT :limit
                        """)
                .param("cutoff", java.sql.Timestamp.from(cutoff))
                .param("limit", limit)
                .query(LeaseRepository::mapLease)
                .list();
    }

    /**
     * Moves a lease to a terminal state, but only from ACTIVE.
     *
     * <p>The state guard makes this safe to run concurrently: if two reaper
     * instances race, exactly one update matches a row and the other reports
     * zero, so the database is dropped once rather than twice.
     *
     * @return true when this caller won the transition
     */
    public boolean close(UUID id, LeaseState newState, Instant at) {
        return jdbc.sql("""
                        UPDATE lease SET state = :state, closed_at = :at
                        WHERE id = :id AND state = 'ACTIVE'
                        """)
                .param("state", newState.name())
                .param("at", java.sql.Timestamp.from(at))
                .param("id", id)
                .update() == 1;
    }

    public boolean renew(UUID id, Instant newExpiry) {
        return jdbc.sql("""
                        UPDATE lease SET expires_at = :expiry, renewals = renewals + 1
                        WHERE id = :id AND state = 'ACTIVE'
                        """)
                .param("expiry", java.sql.Timestamp.from(newExpiry))
                .param("id", id)
                .update() == 1;
    }

    public int countActive() {
        return jdbc.sql("SELECT count(*) FROM lease WHERE state = 'ACTIVE'")
                .query(Integer.class)
                .single();
    }

    /** Database names the control plane believes are live, for the reaper's orphan sweep. */
    public List<String> activeDatabaseNames() {
        return jdbc.sql("SELECT database_name FROM lease WHERE state = 'ACTIVE'")
                .query(String.class)
                .list();
    }

    static Lease mapLease(ResultSet rs, int rowNum) throws SQLException {
        return new Lease(
                rs.getObject("id", UUID.class),
                rs.getObject("dataset_id", UUID.class),
                rs.getString("database_name"),
                rs.getString("jdbc_url"),
                rs.getString("username"),
                null,
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                LeaseState.valueOf(rs.getString("state")),
                rs.getInt("renewals"),
                rs.getString("holder"),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant());
    }
}
