package com.helios.testforge.provision;

import com.helios.testforge.config.TestForgeProperties;
import com.helios.testforge.util.Pg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Creates and destroys the ephemeral databases datasets are seeded into.
 *
 * <p>Each dataset gets its own database and its own login role. Isolating at the
 * database level rather than the schema level means dropping a lease is a single
 * DROP DATABASE, no leftover objects can survive it, and one team's dataset
 * cannot see another's however hard it looks.
 *
 * <p><b>Safety.</b> Every name this class creates carries the configured prefix,
 * and every drop refuses to act on a name that lacks it. That check is the
 * difference between a reaper that cleans up test databases and a reaper that
 * one bad row in the lease table could point at a production database.
 */
@Component
public class EphemeralDatabaseProvisioner {

    private static final Logger log = LoggerFactory.getLogger(EphemeralDatabaseProvisioner.class);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Leaves room for the prefix, a slug and a suffix inside PostgreSQL's 63-byte identifier limit. */
    private static final int MAX_SLUG_LENGTH = 24;

    private final TestForgeProperties.Ephemeral config;

    public EphemeralDatabaseProvisioner(TestForgeProperties properties) {
        this.config = properties.ephemeral();
    }

    /**
     * Creates a database and a role that owns it.
     *
     * @param datasetId the dataset being provisioned
     * @param label     a human-meaningful hint, folded into the database name
     */
    public ProvisionedDatabase provision(UUID datasetId, String label) {
        String suffix = datasetId.toString().replace("-", "").substring(0, 10);
        String databaseName = config.databasePrefix() + slugify(label) + "_" + suffix;
        String roleName = databaseName + "_r";
        String password = generatePassword();

        enforceDatabaseCeiling();

        // CREATE DATABASE cannot run inside a transaction block, so this whole
        // sequence runs with autocommit on rather than under a transaction.
        try (Connection admin = adminConnection()) {
            admin.setAutoCommit(true);
            try (Statement statement = admin.createStatement()) {
                statement.execute("CREATE ROLE " + Pg.quoteIdentifier(roleName)
                        + " LOGIN PASSWORD " + Pg.quoteLiteral(password)
                        + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
                try {
                    statement.execute("CREATE DATABASE " + Pg.quoteIdentifier(databaseName)
                            + " OWNER " + Pg.quoteIdentifier(roleName));
                } catch (SQLException e) {
                    // Do not strand a role with no database attached to it.
                    dropRoleQuietly(statement, roleName);
                    throw e;
                }
            }
        } catch (SQLException e) {
            throw new ProvisioningException("failed to provision database " + databaseName, e);
        }

        String jdbcUrl = "jdbc:postgresql://" + config.clientHost() + ":" + config.clientPort() + "/" + databaseName;
        log.info("Provisioned ephemeral database {} owned by {}", databaseName, roleName);
        return new ProvisionedDatabase(datasetId, databaseName, roleName, password, jdbcUrl, Instant.now());
    }

    /**
     * Applies the schema DDL to a provisioned database.
     *
     * <p>Runs as the owning role rather than as the admin, so every object is
     * owned by the lease's role and the requester can alter their own dataset.
     */
    public void applyDdl(ProvisionedDatabase database, List<String> statements) {
        long start = System.nanoTime();
        try (Connection connection = connectAsOwner(database)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String ddl : statements) {
                    statement.execute(ddl);
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new ProvisioningException("failed to apply schema to " + database.databaseName(), e);
        }
        log.info("Applied {} DDL statements to {} in {} ms",
                statements.size(), database.databaseName(), (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Drops a database and its role.
     *
     * <p>Sessions still connected are terminated first: a single idle psql
     * session would otherwise block the drop indefinitely and leak the database
     * past its lease.
     *
     * @return true when the database was dropped, false when it was already gone
     */
    public boolean drop(String databaseName, String roleName) {
        requireOwnedName(databaseName);
        if (roleName != null) {
            requireOwnedName(roleName);
        }

        try (Connection admin = adminConnection()) {
            admin.setAutoCommit(true);
            try (PreparedStatement terminate = admin.prepareStatement(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                            + "WHERE datname = ? AND pid <> pg_backend_pid()")) {
                terminate.setString(1, databaseName);
                terminate.execute();
            }

            try (Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + Pg.quoteIdentifier(databaseName));
                if (roleName != null) {
                    dropRoleQuietly(statement, roleName);
                }
            }
            log.info("Dropped ephemeral database {} and role {}", databaseName, roleName);
            return true;
        } catch (SQLException e) {
            throw new ProvisioningException("failed to drop database " + databaseName, e);
        }
    }

    /** Every database on the cluster this platform owns, used by the reaper's orphan sweep. */
    public List<String> listOwnedDatabases() {
        List<String> databases = new ArrayList<>();
        try (Connection admin = adminConnection();
             PreparedStatement ps = admin.prepareStatement(
                     "SELECT datname FROM pg_database WHERE datname LIKE ? ORDER BY datname")) {
            ps.setString(1, config.databasePrefix() + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    databases.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new ProvisioningException("failed to list ephemeral databases", e);
        }
        return databases;
    }

    /** A connection to a provisioned database as its owning role. */
    public Connection connectAsOwner(ProvisionedDatabase database) throws SQLException {
        if (database.password() == null) {
            throw new IllegalArgumentException(
                    "cannot connect to " + database.databaseName() + ": the password has been redacted");
        }
        return DriverManager.getConnection(database.jdbcUrl(), database.roleName(), database.password());
    }

    // -------------------------------------------------------------- internals

    private Connection adminConnection() throws SQLException {
        if (config.adminJdbcUrl() == null || config.adminJdbcUrl().isBlank()) {
            throw new ProvisioningException(
                    "testforge.ephemeral.admin-jdbc-url is not configured, so no ephemeral database can be created");
        }
        return DriverManager.getConnection(config.adminJdbcUrl(), config.adminUsername(), config.adminPassword());
    }

    private void enforceDatabaseCeiling() {
        int existing = listOwnedDatabases().size();
        if (existing >= config.maxDatabases()) {
            throw new ProvisioningException("the cluster already holds " + existing
                    + " ephemeral databases, at the configured ceiling of " + config.maxDatabases()
                    + ". Release a lease, or wait for the reaper.");
        }
    }

    /**
     * Refuses to touch anything outside the platform's own namespace.
     *
     * <p>This is the last line of defence before a DROP. It is checked on the
     * name about to be dropped rather than trusted from the caller, because the
     * caller is a reaper reading rows out of a database table.
     */
    private void requireOwnedName(String name) {
        if (name == null || !name.startsWith(config.databasePrefix())) {
            throw new IllegalArgumentException("refusing to drop '" + name
                    + "': it does not carry the ephemeral prefix '" + config.databasePrefix()
                    + "', so it was not created by TestForge");
        }
    }

    /**
     * A role can outlive its database if it still owns objects elsewhere.
     * Failing to drop it must not fail the whole cleanup, or one stuck role
     * would block every future reap.
     */
    private void dropRoleQuietly(Statement statement, String roleName) {
        try {
            statement.execute("DROP ROLE IF EXISTS " + Pg.quoteIdentifier(roleName));
        } catch (SQLException e) {
            log.warn("Could not drop role {}: {}", roleName, e.getMessage());
        }
    }

    /** Reduces a label to something legal and readable inside an identifier. */
    static String slugify(String label) {
        if (label == null || label.isBlank()) {
            return "ds";
        }
        String slug = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            return "ds";
        }
        return slug.length() > MAX_SLUG_LENGTH ? slug.substring(0, MAX_SLUG_LENGTH) : slug;
    }

    /** 192 bits of entropy, URL-safe so it survives being pasted into a connection string. */
    private static String generatePassword() {
        byte[] entropy = new byte[24];
        SECURE_RANDOM.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    /** Raised when an ephemeral database cannot be created, prepared or destroyed. */
    public static class ProvisioningException extends RuntimeException {

        public ProvisioningException(String message) {
            super(message);
        }

        public ProvisioningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
