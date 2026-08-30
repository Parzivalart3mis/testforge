package com.helios.testforge.pipeline;

import com.helios.testforge.config.TestForgeProperties;
import com.helios.testforge.ddl.DdlWriter;
import com.helios.testforge.domain.plan.GenerationPlan;
import com.helios.testforge.domain.request.DatasetRequest;
import com.helios.testforge.domain.request.MaskingPolicy;
import com.helios.testforge.domain.schema.ForeignKey;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.domain.schema.TableRef;
import com.helios.testforge.generate.DatasetGenerator;
import com.helios.testforge.generate.GenerationPlanner;
import com.helios.testforge.generate.GeneratorResolver;
import com.helios.testforge.introspect.PostgresSchemaIntrospector;
import com.helios.testforge.mask.Checksums;
import com.helios.testforge.mask.MaskingEngine;
import com.helios.testforge.provision.EphemeralDatabaseProvisioner;
import com.helios.testforge.provision.ProvisionedDatabase;
import com.helios.testforge.seed.SeedResult;
import com.helios.testforge.seed.Seeder;
import com.helios.testforge.support.DemoSchema;
import com.helios.testforge.support.DockerAvailability;
import com.helios.testforge.support.PostgresContainer;
import com.helios.testforge.util.Pg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole pipeline, against a real database, on a real 22-table schema.
 *
 * <p>Introspect, plan, provision, apply DDL, generate, seed, fix up the cycle,
 * resync sequences — then verify the result by querying it, not by trusting the
 * code that wrote it. The referential-integrity checks in particular are run as
 * SQL against the seeded database, so they would catch a foreign key that was
 * silently never created as well as one that was violated.
 *
 * <p>Wired by hand rather than through Spring. The point is to exercise these
 * specific collaborators against PostgreSQL; a context would add startup cost
 * and hide which component actually failed.
 */
@EnabledIf(value = "com.helios.testforge.support.DockerAvailability#isPresent",
        disabledReason = "no container runtime is available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndProvisioningIT {

    private static final Logger log = LoggerFactory.getLogger(EndToEndProvisioningIT.class);

    /** Rows for root tables. Enough that fan-out produces a few thousand rows overall. */
    private static final int SCALE = 40;

    private static final UUID DATASET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static SchemaSnapshot snapshot;
    private static GenerationPlan plan;
    private static SeedResult seedResult;
    private static ProvisionedDatabase database;
    private static EphemeralDatabaseProvisioner provisioner;
    private static Duration wallClock;

    @BeforeAll
    void provisionAndSeed() throws Exception {
        if (!DockerAvailability.isPresent()) {
            return;
        }
        Instant started = Instant.now();

        TestForgeProperties properties = properties();
        provisioner = new EphemeralDatabaseProvisioner(properties);
        GeneratorResolver resolver = new GeneratorResolver();
        MaskingEngine masking = MaskingEngine.withKey("end-to-end-test-key");
        DatasetGenerator generator = new DatasetGenerator(resolver, masking);
        Seeder seeder = new Seeder(generator, properties);
        DdlWriter ddlWriter = new DdlWriter();

        // ---- introspect ----------------------------------------------------
        snapshot = new PostgresSchemaIntrospector().introspect(DemoSchema.dataSource(), DemoSchema.SCHEMA);

        // ---- plan ----------------------------------------------------------
        DatasetRequest request = new DatasetRequest(
                "e2e commerce", "end-to-end verification", "ci@example.com",
                "demo-commerce", DemoSchema.SCHEMA,
                List.of(), List.of(), SCALE, Map.of(),
                20260829L, Duration.ofHours(1), MaskingPolicy.defaults(), false);

        plan = new GenerationPlanner(resolver, properties).plan(snapshot, request, request.seed());

        // ---- provision and seed --------------------------------------------
        database = provisioner.provision(DATASET_ID, "e2e-commerce");
        provisioner.applyDdl(database, ddlWriter.write(snapshot));

        try (Connection connection = provisioner.connectAsOwner(database)) {
            seedResult = seeder.seed(connection, snapshot, plan, DATASET_ID.toString(), null);
        }

        wallClock = Duration.between(started, Instant.now());
        log.info("End-to-end run: {} tables, {} rows, {} ms wall clock",
                plan.tableCount(), seedResult.totalRows(), wallClock.toMillis());
    }

    @AfterAll
    void dropEphemeralDatabase() {
        if (database != null && provisioner != null) {
            provisioner.drop(database.databaseName(), database.roleName());
        }
    }

    private static TestForgeProperties properties() {
        return new TestForgeProperties(
                new TestForgeProperties.Generation(SCALE, 100_000, 5_000_000L, 500, 1, 3),
                new TestForgeProperties.Masking("end-to-end-test-key", true, "[REDACTED]"),
                new TestForgeProperties.Ephemeral(
                        PostgresContainer.maintenanceJdbcUrl(),
                        PostgresContainer.username(),
                        PostgresContainer.password(),
                        PostgresContainer.host(),
                        PostgresContainer.port(),
                        "tf_", 50),
                null, null, null, List.of());
    }

    // ------------------------------------------------------------- planning

    @Test
    void thePlanCoversEveryTableAndBreaksTheCycle() {
        assertThat(plan.tableCount()).isEqualTo(22);
        assertThat(plan.totalRows()).isPositive();

        assertThat(plan.warnings())
                .as("customer <-> order_header is a genuine cycle and must be reported as broken")
                .anyMatch(warning -> warning.contains("Broke a foreign-key cycle"));

        assertThat(plan.tablesNeedingFixup())
                .singleElement()
                .satisfies(table -> assertThat(table.table().name()).isEqualTo("customer"));
    }

    @Test
    void theSeedOrderPutsEveryParentBeforeItsChildren() {
        List<String> order = plan.tables().stream().map(t -> t.table().qualified()).toList();

        for (ForeignKey fk : snapshot.allForeignKeys()) {
            if (fk.isSelfReference()) {
                continue;
            }
            boolean deferred = plan.tables().stream()
                    .flatMap(t -> t.deferredEdges().stream())
                    .anyMatch(deferredFk -> deferredFk.name().equals(fk.name()));
            if (deferred) {
                continue;
            }
            assertThat(order.indexOf(fk.parent().qualified()))
                    .as("%s must be seeded before %s", fk.parent(), fk.child())
                    .isLessThan(order.indexOf(fk.child().qualified()));
        }
    }

    @Test
    void everySensitiveColumnIsPlannedForMasking() {
        assertThat(plan.maskedColumns()).isPositive();

        List<String> summary = plan.maskingSummary();
        assertThat(summary)
                .anyMatch(entry -> entry.startsWith("public.customer.email"))
                .anyMatch(entry -> entry.startsWith("public.payment.card_number"))
                .anyMatch(entry -> entry.startsWith("public.supplier.iban"));

        assertThat(summary)
                .as("masking a key would break the referential integrity the platform guarantees")
                .noneMatch(entry -> entry.startsWith("public.order_header.customer_id"));
    }

    // -------------------------------------------------------------- seeding

    @Test
    void everyTableWasSeeded() {
        assertThat(seedResult.rowsPerTable()).hasSize(22);
        assertThat(seedResult.rowsPerTable().values()).allSatisfy(rows -> assertThat(rows).isPositive());
        assertThat(seedResult.totalRows()).isEqualTo(
                seedResult.rowsPerTable().values().stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void theSeededRowCountsMatchWhatIsActuallyInTheDatabase() throws SQLException {
        try (Connection connection = provisioner.connectAsOwner(database)) {
            for (var entry : seedResult.rowsPerTable().entrySet()) {
                TableRef ref = TableRef.parse(entry.getKey(), DemoSchema.SCHEMA);
                assertThat(count(connection, "SELECT count(*) FROM " + ref.quoted()))
                        .as("%s", entry.getKey())
                        .isEqualTo(entry.getValue());
            }
        }
    }

    /**
     * The headline claim, verified against the database rather than the code
     * that wrote it: no foreign key anywhere points at a row that does not
     * exist. A constraint that was silently never created would show up here.
     */
    @Test
    void noForeignKeyAnywherePointsAtAMissingRow() throws SQLException {
        List<String> violations = new ArrayList<>();

        try (Connection connection = provisioner.connectAsOwner(database)) {
            for (ForeignKey fk : snapshot.allForeignKeys()) {
                long orphans = countOrphans(connection, fk);
                if (orphans > 0) {
                    violations.add(fk.describe() + " has " + orphans + " orphaned row(s)");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void everyForeignKeyConstraintWasActuallyCreatedInTheEphemeralDatabase() throws SQLException {
        try (Connection connection = provisioner.connectAsOwner(database)) {
            long created = count(connection, """
                    SELECT count(*) FROM pg_constraint con
                    JOIN pg_class c ON c.oid = con.conrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND con.contype = 'f'
                    """);

            assertThat(created)
                    .as("the integrity checks are only meaningful if the constraints exist")
                    .isEqualTo(snapshot.foreignKeyCount());
        }
    }

    @Test
    void theCycleBreakingColumnWasFilledByTheSecondPass() throws SQLException {
        try (Connection connection = provisioner.connectAsOwner(database)) {
            long filled = count(connection,
                    "SELECT count(*) FROM public.customer WHERE primary_order_id IS NOT NULL");

            assertThat(filled)
                    .as("seeded NULL to break the cycle, then filled once order_header existed")
                    .isPositive();
            assertThat(seedResult.fixupRows()).isPositive();
        }
    }

    @Test
    void selfReferencesPointAtRealRowsOfTheSameTable() throws SQLException {
        try (Connection connection = provisioner.connectAsOwner(database)) {
            assertThat(count(connection, """
                    SELECT count(*) FROM public.employee e
                    LEFT JOIN public.employee m ON m.id = e.manager_id
                    WHERE e.manager_id IS NOT NULL AND m.id IS NULL
                    """)).isZero();

            assertThat(count(connection,
                    "SELECT count(*) FROM public.employee WHERE manager_id IS NOT NULL"))
                    .as("a self-reference that is always NULL would not be exercising anything")
                    .isPositive();
        }
    }

    @Test
    void junctionTablesWithAllForeignKeyPrimaryKeysHaveNoDuplicates() throws SQLException {
        try (Connection connection = provisioner.connectAsOwner(database)) {
            // A duplicate would have aborted the insert, so reaching here at all
            // is most of the proof; this confirms rows were actually produced.
            assertThat(count(connection, "SELECT count(*) FROM public.inventory")).isPositive();
            assertThat(count(connection, "SELECT count(*) FROM public.price_list_entry")).isPositive();
            assertThat(count(connection, """
                    SELECT count(*) FROM (
                        SELECT warehouse_id, product_variant_id
                        FROM public.inventory
                        GROUP BY warehouse_id, product_variant_id
                        HAVING count(*) > 1
                    ) duplicates
                    """)).isZero();
        }
    }

    @Test
    void theStoredGeneratedColumnWasComputedByTheDatabase() throws SQLException {
        try (Connection connection = provisioner.connectAsOwner(database)) {
            assertThat(count(connection, """
                    SELECT count(*) FROM public.employee
                    WHERE full_name IS DISTINCT FROM (first_name || ' ' || last_name)
                    """))
                    .as("the seeder must leave a STORED generated column out of the INSERT")
                    .isZero();
        }
    }

    @Test
    void identitySequencesWereAdvancedPastTheSeededKeys() throws SQLException {
        try (Connection connection = provisioner.connectAsOwner(database)) {
            // The single most annoying way a seeded database fails: the first
            // insert an application makes collides with row 1.
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT nextval(pg_get_serial_sequence('public.customer', 'id'))")) {
                assertThat(rs.next()).isTrue();
                long next = rs.getLong(1);
                long max = count(connection, "SELECT COALESCE(max(id), 0) FROM public.customer");
                assertThat(next).isGreaterThan(max);
            }
        }
    }

    // -------------------------------------------------------------- masking

    @Test
    void everySeededEmailIsAtAReservedDomain() throws SQLException {
        try (Connection connection = provisioner.connectAsOwner(database)) {
            assertThat(count(connection,
                    "SELECT count(*) FROM public.customer WHERE email NOT LIKE '%example.com' "
                            + "AND email NOT LIKE '%example.net' AND email NOT LIKE '%example.org'"))
                    .as("RFC 2606 domains cannot receive mail, so test code cannot reach a real inbox")
                    .isZero();
        }
    }

    @Test
    void everySeededNationalIdIsInTheReservedRange() throws SQLException {
        try (Connection connection = provisioner.connectAsOwner(database)) {
            assertThat(count(connection, """
                    SELECT count(*) FROM public.customer
                    WHERE national_id IS NOT NULL
                      AND substring(national_id from 1 for 3)::int NOT BETWEEN 900 AND 999
                    """))
                    .as("900-999 is never issued, so a seeded value cannot be a real person's")
                    .isZero();
        }
    }

    @Test
    void everySeededCardNumberPassesLuhn() throws SQLException {
        List<String> cards = new ArrayList<>();
        try (Connection connection = provisioner.connectAsOwner(database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT card_number FROM public.payment WHERE card_number IS NOT NULL");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                cards.add(rs.getString(1));
            }
        }

        assertThat(cards).isNotEmpty();
        assertThat(cards).allSatisfy(card -> assertThat(Checksums.isLuhnValid(card))
                .as("card %s must validate, or the dataset only exercises the rejection path", card)
                .isTrue());
    }

    @Test
    void everySeededIbanPassesItsChecksum() throws SQLException {
        List<String> ibans = new ArrayList<>();
        try (Connection connection = provisioner.connectAsOwner(database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT iban FROM public.supplier WHERE iban IS NOT NULL");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                ibans.add(rs.getString(1));
            }
        }

        assertThat(ibans).isNotEmpty();
        assertThat(ibans).allSatisfy(iban ->
                assertThat(Checksums.isIbanValid(iban)).as("IBAN %s", iban).isTrue());
    }

    @Test
    void enumColumnsHoldOnlyDeclaredLabels() throws SQLException {
        try (Connection connection = provisioner.connectAsOwner(database)) {
            assertThat(count(connection, """
                    SELECT count(*) FROM public.order_header
                    WHERE status::text NOT IN
                        ('DRAFT', 'PLACED', 'PAID', 'FULFILLED', 'CANCELLED', 'REFUNDED')
                    """)).isZero();
        }
    }

    @Test
    void checkConstraintsHoldAcrossTheSeededData() throws SQLException {
        try (Connection connection = provisioner.connectAsOwner(database)) {
            assertThat(count(connection,
                    "SELECT count(*) FROM public.review WHERE rating NOT BETWEEN 1 AND 5")).isZero();
            assertThat(count(connection,
                    "SELECT count(*) FROM public.order_line WHERE quantity <= 0")).isZero();
            assertThat(count(connection,
                    "SELECT count(*) FROM public.customer WHERE lifetime_value < 0")).isZero();
        }
    }

    @Test
    void nullableColumnsCarryBothNullsAndValuesSoBothPathsAreExercised() throws SQLException {
        try (Connection connection = provisioner.connectAsOwner(database)) {
            long total = count(connection, "SELECT count(*) FROM public.customer");
            long nullPhones = count(connection,
                    "SELECT count(*) FROM public.customer WHERE phone IS NULL");

            assertThat(nullPhones).isPositive().isLessThan(total);
        }
    }

    // ------------------------------------------------------------ behaviour

    @Test
    void aTwentyTwoTableDatasetIsReadyWellInsideNinetySeconds() {
        log.info("Wall clock for the full 22-table run: {} ms", wallClock.toMillis());

        assertThat(wallClock)
                .as("the whole point is that this beats hand-writing fixtures")
                .isLessThan(Duration.ofSeconds(90));
    }

    // -------------------------------------------------------------- helpers

    private static long count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** Rows whose foreign key is set but resolves to nothing in the parent table. */
    private static long countOrphans(Connection connection, ForeignKey fk) throws SQLException {
        StringBuilder join = new StringBuilder();
        StringBuilder notNull = new StringBuilder();
        for (int i = 0; i < fk.childColumns().size(); i++) {
            if (i > 0) {
                join.append(" AND ");
                notNull.append(" AND ");
            }
            join.append("p.").append(Pg.quoteIdentifier(fk.parentColumns().get(i)))
                    .append(" = c.").append(Pg.quoteIdentifier(fk.childColumns().get(i)));
            notNull.append("c.").append(Pg.quoteIdentifier(fk.childColumns().get(i))).append(" IS NOT NULL");
        }

        String sql = "SELECT count(*) FROM " + fk.child().quoted() + " c"
                + " LEFT JOIN " + fk.parent().quoted() + " p ON " + join
                + " WHERE " + notNull
                + " AND p." + Pg.quoteIdentifier(fk.parentColumns().getFirst()) + " IS NULL";
        return count(connection, sql);
    }
}
