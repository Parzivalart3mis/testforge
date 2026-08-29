package com.helios.testforge.generate;

import com.helios.testforge.config.TestForgeProperties;
import com.helios.testforge.domain.plan.ColumnPlan;
import com.helios.testforge.domain.plan.ColumnRole;
import com.helios.testforge.domain.plan.GenerationPlan;
import com.helios.testforge.domain.plan.TablePlan;
import com.helios.testforge.domain.request.DatasetRequest;
import com.helios.testforge.domain.request.MaskStrategy;
import com.helios.testforge.domain.request.MaskingPolicy;
import com.helios.testforge.domain.request.MaskingRule;
import com.helios.testforge.domain.schema.DataClass;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.mask.MaskingEngine;
import com.helios.testforge.support.SchemaFixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.helios.testforge.support.SchemaFixtures.ref;
import static com.helios.testforge.support.SchemaFixtures.table;
import static org.assertj.core.api.Assertions.assertThat;

class DatasetGeneratorTest {

    private final GeneratorResolver resolver = new GeneratorResolver();
    private final MaskingEngine masking = MaskingEngine.withKey("generator-test-key");
    private final DatasetGenerator generator = new DatasetGenerator(resolver, masking);
    private final GenerationPlanner planner = new GenerationPlanner(resolver, defaults());

    private static TestForgeProperties defaults() {
        return new TestForgeProperties(null, null, null, null, null, null, List.of());
    }

    private static DatasetRequest request(int scale) {
        return new DatasetRequest("test", null, "tester", "target", "public",
                List.of(), List.of(), scale, Map.of(), 42L, null, MaskingPolicy.defaults(), false);
    }

    /** Generates the whole dataset and returns rows keyed by qualified table name. */
    private Map<String, List<Map<String, Object>>> generateAll(SchemaSnapshot snapshot,
                                                               DatasetRequest datasetRequest,
                                                               String datasetId) {
        GenerationPlan plan = planner.plan(snapshot, datasetRequest, datasetRequest.seed());
        KeyPool pool = generator.newKeyPool(snapshot, plan);
        Map<String, List<Map<String, Object>>> byTable = new java.util.LinkedHashMap<>();
        for (TablePlan tablePlan : plan.tables()) {
            byTable.put(tablePlan.table().qualified(),
                    generator.generateTable(snapshot, plan, tablePlan, pool, datasetId));
        }
        return byTable;
    }

    @Nested
    class ReferentialConsistency {

        @Test
        void everyForeignKeyResolvesToAParentThatWasActuallyGenerated() {
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("country"),
                    table("customer").references("country_id", "country", false),
                    table("order_header").references("customer_id", "customer", false),
                    table("order_line").references("order_id", "order_header", false));

            var rows = generateAll(snapshot, request(20), "ds-1");

            assertReferencesResolve(rows, "public.customer", "country_id", "public.country", "id");
            assertReferencesResolve(rows, "public.order_header", "customer_id", "public.customer", "id");
            assertReferencesResolve(rows, "public.order_line", "order_id", "public.order_header", "id");
        }

        @Test
        void aNullableForeignKeyIsEitherNullOrResolves() {
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("promo"),
                    table("order_header").references("promo_id", "promo", true));

            var rows = generateAll(snapshot, request(30), "ds-2");
            Set<Object> promoIds = idsOf(rows.get("public.promo"));

            assertThat(rows.get("public.order_header"))
                    .allSatisfy(row -> {
                        Object promoId = row.get("promo_id");
                        if (promoId != null) {
                            assertThat(promoIds).contains(promoId);
                        }
                    });
        }

        @Test
        void aSelfReferenceOnlyEverPointsAtAnEarlierRow() {
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("employee").references("manager_id", "employee", true));

            var rows = generateAll(snapshot, request(50), "ds-3").get("public.employee");

            Set<Object> seenIds = new HashSet<>();
            for (Map<String, Object> row : rows) {
                Object managerId = row.get("manager_id");
                if (managerId != null) {
                    assertThat(seenIds)
                            .as("a manager must already exist when the employee row is inserted")
                            .contains(managerId);
                }
                seenIds.add(row.get("id"));
            }
            assertThat(rows).anyMatch(row -> row.get("manager_id") != null);
        }

        @Test
        void aCycleBreakingColumnIsSeededNullAndFilledByTheSecondPass() {
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("customer").references("primary_order_id", "order_header", true),
                    table("order_header").references("customer_id", "customer", false));

            GenerationPlan plan = planner.plan(snapshot, request(15), 42L);
            KeyPool pool = generator.newKeyPool(snapshot, plan);

            List<Map<String, Object>> customers = null;
            for (TablePlan tablePlan : plan.tables()) {
                var generated = generator.generateTable(snapshot, plan, tablePlan, pool, "ds-4");
                if (tablePlan.table().equals(ref("customer"))) {
                    customers = generated;
                }
            }

            assertThat(customers).allSatisfy(row ->
                    assertThat(row.get("primary_order_id"))
                            .as("the deferred column must be NULL on insert")
                            .isNull());

            TablePlan customerPlan = plan.forTable(ref("customer")).orElseThrow();
            var fixups = generator.generateDeferredValues(ref("customer"),
                    customerPlan.deferredEdges().getFirst(), customers.size(), pool, plan.seed());

            assertThat(fixups).hasSize(customers.size());
            assertThat(fixups).allSatisfy(update ->
                    assertThat(update.get("primary_order_id"))
                            .as("the second pass fills it once orders exist")
                            .isNotNull());
        }
    }

    @Nested
    class Reproducibility {

        @Test
        void theSameSeedProducesAByteIdenticalDataset() {
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("account").classified("email", "varchar", false, DataClass.EMAIL),
                    table("entry").references("account_id", "account", false)
                            .classified("amount", "numeric", false, DataClass.MONETARY_AMOUNT));

            var first = generateAll(snapshot, request(25), "ds-fixed");
            var second = generateAll(snapshot, request(25), "ds-fixed");

            assertThat(second).isEqualTo(first);
        }

        @Test
        void aDifferentSeedProducesADifferentDataset() {
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("account").classified("email", "varchar", false, DataClass.EMAIL));

            DatasetRequest withSeedOne = new DatasetRequest("t", null, "u", "target", "public",
                    List.of(), List.of(), 25, Map.of(), 1L, null, MaskingPolicy.defaults(), false);
            DatasetRequest withSeedTwo = new DatasetRequest("t", null, "u", "target", "public",
                    List.of(), List.of(), 25, Map.of(), 2L, null, MaskingPolicy.defaults(), false);

            assertThat(generateAll(snapshot, withSeedTwo, "ds"))
                    .isNotEqualTo(generateAll(snapshot, withSeedOne, "ds"));
        }

        @Test
        void generationOrderWithinATableDoesNotAffectAnyValue() {
            // Every cell derives its own stream, so generating row 7 alone must
            // produce what generating rows 0..7 in sequence produces for row 7.
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("widget").classified("label", "varchar", false, DataClass.TITLE));

            var full = generateAll(snapshot, request(20), "ds").get("public.widget");
            var partial = generateAll(snapshot, request(8), "ds").get("public.widget");

            for (int i = 0; i < partial.size(); i++) {
                assertThat(partial.get(i))
                        .as("row %d must not depend on how many rows follow it", i)
                        .isEqualTo(full.get(i));
            }
        }
    }

    @Nested
    class Constraints {

        @Test
        void primaryKeysAreUniqueAndCountFromOne() {
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(table("thing"));

            var rows = generateAll(snapshot, request(40), "ds").get("public.thing");

            assertThat(idsOf(rows)).hasSize(40);
            assertThat(rows.getFirst().get("id")).isEqualTo(1);
            assertThat(rows.getLast().get("id")).isEqualTo(40);
        }

        @Test
        void aUniqueColumnHasNoDuplicatesAcrossAWholeTable() {
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("account").classified("email", "varchar", false, DataClass.EMAIL).unique("email"));

            var rows = generateAll(snapshot, request(500), "ds").get("public.account");

            assertThat(rows.stream().map(row -> row.get("email")).collect(java.util.stream.Collectors.toSet()))
                    .as("a duplicate would abort seeding on the UNIQUE constraint")
                    .hasSize(500);
        }

        @Test
        void aCompositeKeyMadeOfForeignKeysStaysUnique() {
            SchemaFixtures.Builder junction = table("order_product")
                    .references("order_id", "order_header", false)
                    .references("product_id", "product", false);

            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("order_header"), table("product"), junction);

            // Rebuild the junction with a composite primary key over its two FKs.
            var tables = new ArrayList<>(snapshot.tables());
            var junctionMeta = tables.removeLast();
            tables.add(new com.helios.testforge.domain.schema.TableMeta(
                    junctionMeta.ref(), junctionMeta.columns(),
                    new com.helios.testforge.domain.schema.PrimaryKey(
                            "pk_order_product", List.of("order_id", "product_id")),
                    junctionMeta.foreignKeys(), List.of(), List.of(), -1, null));
            SchemaSnapshot withComposite = new SchemaSnapshot(
                    snapshot.database(), snapshot.schema(), snapshot.capturedAt(), tables, "fixture");

            var rows = generateAll(withComposite, request(20), "ds").get("public.order_product");

            Set<List<Object>> keys = new HashSet<>();
            for (Map<String, Object> row : rows) {
                assertThat(keys.add(List.of(row.get("order_id"), row.get("product_id"))))
                        .as("composite key must not repeat")
                        .isTrue();
            }
            assertThat(rows).isNotEmpty();
        }

        @Test
        void nullableColumnsGetSomeNullsSoNullPathsAreExercised() {
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("note").classified("body", "text", true, DataClass.FREE_TEXT));

            var rows = generateAll(snapshot, request(300), "ds").get("public.note");

            long nulls = rows.stream().filter(row -> row.get("body") == null).count();
            assertThat(nulls).isBetween(1L, 120L);
        }

        @Test
        void notNullColumnsNeverGetNull() {
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("invoice")
                            .classified("reference", "varchar", false, DataClass.TITLE)
                            .classified("total", "numeric", false, DataClass.MONETARY_AMOUNT));

            assertThat(generateAll(snapshot, request(100), "ds").get("public.invoice"))
                    .allSatisfy(row -> {
                        assertThat(row.get("reference")).isNotNull();
                        assertThat(row.get("total")).isNotNull();
                    });
        }

        @Test
        void generatedValuesRespectTheColumnsDeclaredLength() {
            var column = new com.helios.testforge.domain.schema.ColumnMeta(
                    "code", 2, "character varying(8)", "varchar", "character varying(8)", false,
                    8, null, null, null, false, null, false, null, false,
                    List.of(), null, null, DataClass.TITLE);
            var meta = new com.helios.testforge.domain.schema.TableMeta(
                    ref("part"), List.of(SchemaFixtures.idColumn(1), column),
                    new com.helios.testforge.domain.schema.PrimaryKey("pk_part", List.of("id")),
                    List.of(), List.of(), List.of(), -1, null);
            SchemaSnapshot snapshot = new SchemaSnapshot(
                    "testdb", "public", java.time.Instant.EPOCH, List.of(meta), "fixture");

            assertThat(generateAll(snapshot, request(100), "ds").get("public.part"))
                    .allSatisfy(row -> assertThat((String) row.get("code")).hasSizeLessThanOrEqualTo(8));
        }
    }

    @Nested
    class Masking {

        @Test
        void aSensitiveColumnIsMaskedOnItsWayIntoTheDataset() {
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("person").classified("ssn", "varchar", false, DataClass.SSN));

            var rows = generateAll(snapshot, request(50), "ds").get("public.person");

            assertThat(rows).allSatisfy(row -> {
                String ssn = (String) row.get("ssn");
                assertThat(ssn).matches("\\d{3}-\\d{2}-\\d{4}");
                assertThat(Integer.parseInt(ssn.substring(0, 3)))
                        .as("reserved range, so it can never be a real number")
                        .isBetween(900, 999);
            });
        }

        @Test
        void keyColumnsAreNeverMaskedEvenWhenARuleAsksForIt() {
            MaskingPolicy aggressive = new MaskingPolicy(true,
                    List.of(MaskingRule.of("*", "*", MaskStrategy.HASH)));
            DatasetRequest req = new DatasetRequest("t", null, "u", "target", "public",
                    List.of(), List.of(), 20, Map.of(), 7L, null, aggressive, false);

            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("parent"),
                    table("child").references("parent_id", "parent", false));

            GenerationPlan plan = planner.plan(snapshot, req, 7L);
            ColumnPlan parentId = plan.forTable(ref("child")).orElseThrow()
                    .columns().stream().filter(c -> c.column().equals("parent_id")).findFirst().orElseThrow();

            assertThat(parentId.mask()).isEqualTo(MaskStrategy.PRESERVE);
            assertThat(parentId.role()).isEqualTo(ColumnRole.FOREIGN_KEY);
            assertThat(plan.warnings()).anyMatch(w -> w.contains("break referential integrity"));

            // And the generated keys still resolve.
            var rows = generateAll(snapshot, req, "ds");
            assertReferencesResolve(rows, "public.child", "parent_id", "public.parent", "id");
        }

        @Test
        void aMaskedEmailLandsAtAReservedDomainThatCannotReceiveMail() {
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("person").classified("email", "varchar", false, DataClass.EMAIL));

            var rows = generateAll(snapshot, request(50), "ds").get("public.person");

            assertThat(rows).allSatisfy(row -> assertThat((String) row.get("email"))
                    .as("RFC 2606 reserved, so test code cannot accidentally mail a real address")
                    .matches("[a-z]+\\.[a-z]+[0-9]+@[a-z.]*example\\.(com|net|org)"));
        }

        @Test
        void maskingIsScopedToTheDatasetSoTwoDatasetsCannotBeCorrelated() {
            SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                    table("person").classified("ssn", "varchar", false, DataClass.SSN));

            var inFirst = generateAll(snapshot, request(30), "dataset-one").get("public.person");
            var inSecond = generateAll(snapshot, request(30), "dataset-two").get("public.person");

            assertThat(inSecond)
                    .as("the same seed with a different dataset salt must not reproduce the masked values")
                    .isNotEqualTo(inFirst);
        }
    }

    // ------------------------------------------------------------- helpers

    private static Set<Object> idsOf(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> row.get("id")).collect(java.util.stream.Collectors.toSet());
    }

    private static void assertReferencesResolve(Map<String, List<Map<String, Object>>> rows,
                                                String childTable, String childColumn,
                                                String parentTable, String parentColumn) {
        Set<Object> parentKeys = rows.get(parentTable).stream()
                .map(row -> row.get(parentColumn))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(rows.get(childTable)).isNotEmpty();
        assertThat(rows.get(childTable)).allSatisfy(row ->
                assertThat(parentKeys)
                        .as("%s.%s must point at a row that exists in %s", childTable, childColumn, parentTable)
                        .contains(row.get(childColumn)));
    }
}
