package com.helios.testforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Everything the platform is configured with, under the {@code testforge} prefix.
 */
@ConfigurationProperties(prefix = "testforge")
public record TestForgeProperties(
        Generation generation,
        Masking masking,
        Ephemeral ephemeral,
        Leases leases,
        Jobs jobs,
        Snapshots snapshots,
        List<Target> targets) {

    public TestForgeProperties {
        generation = generation == null ? Generation.defaults() : generation;
        masking = masking == null ? Masking.defaults() : masking;
        ephemeral = ephemeral == null ? Ephemeral.defaults() : ephemeral;
        leases = leases == null ? Leases.defaults() : leases;
        jobs = jobs == null ? Jobs.defaults() : jobs;
        snapshots = snapshots == null ? Snapshots.defaults() : snapshots;
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    /**
     * Generation limits and defaults.
     *
     * @param defaultScale     rows generated for a table with no inbound dependency
     * @param maxRowsPerTable  hard ceiling per table, rejecting requests above it
     * @param maxTotalRows     hard ceiling across the whole dataset
     * @param batchSize        rows per JDBC batch when seeding
     * @param fanoutMin        minimum children generated per parent row
     * @param fanoutMax        maximum children generated per parent row
     */
    public record Generation(
            int defaultScale,
            int maxRowsPerTable,
            long maxTotalRows,
            int batchSize,
            int fanoutMin,
            int fanoutMax) {

        public static Generation defaults() {
            return new Generation(100, 1_000_000, 20_000_000L, 1_000, 1, 5);
        }

        public Generation {
            defaultScale = defaultScale <= 0 ? 100 : defaultScale;
            maxRowsPerTable = maxRowsPerTable <= 0 ? 1_000_000 : maxRowsPerTable;
            maxTotalRows = maxTotalRows <= 0 ? 20_000_000L : maxTotalRows;
            batchSize = batchSize <= 0 ? 1_000 : batchSize;
            fanoutMin = Math.max(0, fanoutMin);
            fanoutMax = Math.max(fanoutMin, fanoutMax);
        }
    }

    /**
     * Masking configuration.
     *
     * @param key                    HMAC key backing every deterministic transform
     * @param maskSensitiveByDefault whether inferred-sensitive columns mask without an explicit rule
     * @param redactionToken         the literal substituted by the REDACT strategy
     */
    public record Masking(String key, boolean maskSensitiveByDefault, String redactionToken) {

        public static Masking defaults() {
            return new Masking(null, true, "[REDACTED]");
        }

        public Masking {
            redactionToken = (redactionToken == null || redactionToken.isBlank()) ? "[REDACTED]" : redactionToken;
        }
    }

    /**
     * The PostgreSQL cluster ephemeral databases are created on.
     *
     * @param adminJdbcUrl  JDBC URL of the {@code postgres} maintenance database
     * @param adminUsername role with CREATEDB and CREATEROLE
     * @param adminPassword its password
     * @param clientHost    host the console hands out in connection strings — may differ from the admin host
     * @param clientPort    port the console hands out
     * @param databasePrefix prefix for every database the platform creates, so cleanup can be scoped safely
     * @param maxDatabases  ceiling on concurrently provisioned databases
     */
    public record Ephemeral(
            String adminJdbcUrl,
            String adminUsername,
            String adminPassword,
            String clientHost,
            int clientPort,
            String databasePrefix,
            int maxDatabases) {

        public static Ephemeral defaults() {
            return new Ephemeral(null, null, null, "localhost", 5432, "tf_", 50);
        }

        public Ephemeral {
            databasePrefix = (databasePrefix == null || databasePrefix.isBlank()) ? "tf_" : databasePrefix;
            clientPort = clientPort <= 0 ? 5432 : clientPort;
            maxDatabases = maxDatabases <= 0 ? 50 : maxDatabases;
        }
    }

    /**
     * Lease policy.
     *
     * @param defaultTtl     TTL applied when a request does not ask for one
     * @param maxTtl         longest TTL a request may ask for
     * @param renewBy        how much each renewal adds
     * @param reaperInterval how often expired leases are swept
     * @param reapGrace      extra time after expiry before the database is dropped
     */
    public record Leases(
            Duration defaultTtl,
            Duration maxTtl,
            Duration renewBy,
            Duration reaperInterval,
            Duration reapGrace) {

        public static Leases defaults() {
            return new Leases(Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(2),
                    Duration.ofMinutes(1), Duration.ofMinutes(5));
        }

        public Leases {
            defaultTtl = defaultTtl == null ? Duration.ofHours(4) : defaultTtl;
            maxTtl = maxTtl == null ? Duration.ofHours(24) : maxTtl;
            renewBy = renewBy == null ? Duration.ofHours(2) : renewBy;
            reaperInterval = reaperInterval == null ? Duration.ofMinutes(1) : reaperInterval;
            reapGrace = reapGrace == null ? Duration.ofMinutes(5) : reapGrace;
        }
    }

    /**
     * Job state storage.
     *
     * @param backend   {@code dynamodb} in deployed environments, {@code memory} for local runs
     * @param tableName DynamoDB table holding job records
     * @param workers   size of the pool running provisioning pipelines
     */
    public record Jobs(String backend, String tableName, int workers) {

        public static Jobs defaults() {
            return new Jobs("memory", "testforge-jobs", 4);
        }

        public Jobs {
            backend = (backend == null || backend.isBlank()) ? "memory" : backend.toLowerCase(java.util.Locale.ROOT);
            tableName = (tableName == null || tableName.isBlank()) ? "testforge-jobs" : tableName;
            workers = workers <= 0 ? 4 : workers;
        }
    }

    /**
     * Snapshot export.
     *
     * @param backend   {@code s3} in deployed environments, {@code filesystem} for local runs
     * @param bucket    S3 bucket receiving snapshot bundles
     * @param prefix    key prefix within the bucket
     * @param directory filesystem root, when {@code backend} is filesystem
     */
    public record Snapshots(String backend, String bucket, String prefix, String directory) {

        public static Snapshots defaults() {
            return new Snapshots("filesystem", null, "datasets/", "./testforge-snapshots");
        }

        public Snapshots {
            backend = (backend == null || backend.isBlank()) ? "filesystem" : backend.toLowerCase(java.util.Locale.ROOT);
            prefix = prefix == null ? "" : prefix;
            directory = (directory == null || directory.isBlank()) ? "./testforge-snapshots" : directory;
        }
    }

    /**
     * A registered source schema engineers can request datasets against.
     *
     * <p>TestForge only ever reads a target's catalog — it introspects structure
     * and never copies rows out — so a target may point at a production replica.
     *
     * @param id          stable identifier used in requests
     * @param displayName label shown in the console
     * @param jdbcUrl     JDBC URL of the source database
     * @param username    read-only role with catalog access
     * @param password    its password
     * @param schema      default schema to introspect
     */
    public record Target(
            String id,
            String displayName,
            String jdbcUrl,
            String username,
            String password,
            String schema) {

        public Target {
            schema = (schema == null || schema.isBlank()) ? "public" : schema;
            displayName = (displayName == null || displayName.isBlank()) ? id : displayName;
        }
    }
}
