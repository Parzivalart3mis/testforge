package com.helios.testforge.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates the demo commerce schema on the shared container, so integration
 * tests have a realistic target to introspect.
 *
 * <p>Created once per JVM into its own database. Tests only ever read its
 * catalog, so sharing it between them is safe and saves recreating 22 tables
 * per test class.
 */
public final class DemoSchema {

    public static final String DATABASE = "testforge_demo";
    public static final String SCHEMA = "public";

    private static final String SCRIPT = "db/demo/demo_commerce.sql";

    private static HikariDataSource dataSource;

    private DemoSchema() {
    }

    /** A read-only data source over the demo database, creating it on first use. */
    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            createDatabase();
            applyScript();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(PostgresContainer.jdbcUrlFor(DATABASE));
            config.setUsername(PostgresContainer.username());
            config.setPassword(PostgresContainer.password());
            config.setMaximumPoolSize(3);
            config.setPoolName("demo-schema");
            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }

    private static void createDatabase() {
        try (Connection admin = java.sql.DriverManager.getConnection(
                PostgresContainer.maintenanceJdbcUrl(),
                PostgresContainer.username(),
                PostgresContainer.password());
             Statement statement = admin.createStatement()) {
            admin.setAutoCommit(true);
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create the demo database", e);
        }
    }

    private static void applyScript() {
        String sql = readScript();
        try (Connection connection = java.sql.DriverManager.getConnection(
                PostgresContainer.jdbcUrlFor(DATABASE),
                PostgresContainer.username(),
                PostgresContainer.password());
             Statement statement = connection.createStatement()) {
            // The script has no procedural blocks, so the driver can run it as
            // one multi-statement batch rather than needing a SQL splitter.
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("could not apply the demo schema", e);
        }
    }

    private static String readScript() {
        try (InputStream in = DemoSchema.class.getClassLoader().getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + SCRIPT);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + SCRIPT, e);
        }
    }

    /** The tables the demo schema defines, for tests that assert on its shape. */
    public static final java.util.List<String> TABLES = java.util.List.of(
            "address", "audit_log", "category", "country", "customer", "customer_contact",
            "employee", "inventory", "order_header", "order_line", "payment", "price_list",
            "price_list_entry", "product", "product_variant", "promotion", "region",
            "review", "shipment", "shipment_item", "supplier", "warehouse");
}
