package com.helios.testforge.introspect;

import com.helios.testforge.config.TestForgeProperties;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registered source schemas engineers can request datasets against.
 *
 * <p>Each target gets a small, lazily created connection pool. Small because
 * introspection is five queries every few minutes, not a workload — a target
 * may well point at a production replica, and a test data platform has no
 * business holding a meaningful number of connections open against one.
 *
 * <p>Nothing here ever reads a row of a target's data. Introspection reads
 * {@code pg_catalog} only, which is what makes pointing a target at a replica
 * of production a reasonable thing to do.
 */
@Component
public class TargetRegistry {

    private static final Logger log = LoggerFactory.getLogger(TargetRegistry.class);

    /** Deliberately tiny: a target is read from occasionally, never worked against. */
    private static final int POOL_SIZE = 2;

    private final Map<String, TestForgeProperties.Target> targets = new LinkedHashMap<>();
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final PostgresSchemaIntrospector introspector;

    public TargetRegistry(TestForgeProperties properties, PostgresSchemaIntrospector introspector) {
        this.introspector = introspector;
        for (TestForgeProperties.Target target : properties.targets()) {
            targets.put(target.id(), target);
        }
        log.info("Registered {} target schema(s): {}", targets.size(), targets.keySet());
    }

    public List<TestForgeProperties.Target> all() {
        return List.copyOf(targets.values());
    }

    public Optional<TestForgeProperties.Target> find(String id) {
        return Optional.ofNullable(targets.get(id));
    }

    public TestForgeProperties.Target require(String id) {
        return find(id).orElseThrow(() -> new SchemaIntrospectionException(
                "no target registered with id '" + id + "'. Registered targets: " + targets.keySet()));
    }

    /** Introspects a target's schema, reading its catalog live. */
    public SchemaSnapshot introspect(String targetId, String schema) {
        TestForgeProperties.Target target = require(targetId);
        String effectiveSchema = (schema == null || schema.isBlank()) ? target.schema() : schema;
        return introspector.introspect(dataSourceFor(target), effectiveSchema);
    }

    /** Confirms a target is reachable and its schema readable, without doing the full introspection. */
    public boolean isReachable(String targetId) {
        try {
            TestForgeProperties.Target target = require(targetId);
            try (var connection = dataSourceFor(target).getConnection()) {
                return connection.isValid(5);
            }
        } catch (Exception e) {
            log.warn("Target {} is not reachable: {}", targetId, e.getMessage());
            return false;
        }
    }

    /** A pooled data source for the target, created on first use. */
    public DataSource dataSourceFor(TestForgeProperties.Target target) {
        return pools.computeIfAbsent(target.id(), id -> {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(target.jdbcUrl());
            config.setUsername(target.username());
            config.setPassword(target.password());
            config.setMaximumPoolSize(POOL_SIZE);
            config.setMinimumIdle(0);
            config.setPoolName("testforge-target-" + id);
            config.setConnectionTimeout(10_000);
            // Idle connections against a target are pure cost; drop them quickly.
            config.setIdleTimeout(60_000);
            config.setReadOnly(true);
            log.info("Opened a connection pool for target {} ({})", id, target.jdbcUrl());
            return new HikariDataSource(config);
        });
    }

    @PreDestroy
    void closePools() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
