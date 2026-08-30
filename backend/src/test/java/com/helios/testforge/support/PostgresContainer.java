package com.helios.testforge.support;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container, shared by every integration test in the JVM.
 *
 * <p>Started once in a static initialiser rather than per test class. Spring
 * caches application contexts across classes, so a per-class container would
 * add its startup to every class for no isolation benefit — the tests isolate
 * themselves by creating their own databases, which is what the platform does
 * in production anyway.
 *
 * <p>The container's role is a superuser, which the platform genuinely needs:
 * provisioning creates databases and login roles.
 */
public final class PostgresContainer {

    /** Pinned, so a new PostgreSQL release cannot turn CI red overnight. */
    public static final DockerImageName IMAGE = DockerImageName.parse("postgres:17-alpine");

    private static PostgreSQLContainer instance;

    private PostgresContainer() {
    }

    /** The running container, started on first use. */
    public static synchronized PostgreSQLContainer instance() {
        if (instance == null) {
            instance = new PostgreSQLContainer(IMAGE)
                    .withDatabaseName("testforge")
                    .withUsername("testforge")
                    .withPassword("testforge");
            instance.start();
        }
        return instance;
    }

    /** JDBC URL for the cluster's maintenance database, which provisioning connects to. */
    public static String maintenanceJdbcUrl() {
        PostgreSQLContainer container = instance();
        return "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/postgres";
    }

    /** JDBC URL for a named database on the same cluster. */
    public static String jdbcUrlFor(String database) {
        PostgreSQLContainer container = instance();
        return "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
    }

    public static String host() {
        return instance().getHost();
    }

    public static int port() {
        return instance().getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
    }

    public static String username() {
        return instance().getUsername();
    }

    public static String password() {
        return instance().getPassword();
    }
}
