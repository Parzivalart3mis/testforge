package com.helios.testforge.graph;

import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.domain.schema.TableRef;
import com.helios.testforge.support.SchemaFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.helios.testforge.support.SchemaFixtures.ref;
import static com.helios.testforge.support.SchemaFixtures.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DependencyGraphTest {

    /** Asserts the defining property of a seed order: every parent precedes every child. */
    private void assertParentsPrecedeChildren(DependencyGraph graph, SeedOrder order) {
        for (var fk : graph.edges()) {
            if (order.isDeferred(fk)) {
                continue;
            }
            assertThat(order.order().indexOf(fk.parent()))
                    .as("%s must be seeded before %s", fk.parent(), fk.child())
                    .isLessThan(order.order().indexOf(fk.child()));
        }
    }

    @Test
    void ordersALinearChainParentsFirst() {
        SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                table("country"),
                table("customer").references("country_id", "country", false),
                table("order_header").references("customer_id", "customer", false),
                table("order_line").references("order_id", "order_header", false));

        SeedOrder order = DependencyGraph.of(snapshot).order();

        assertThat(order.order()).containsExactly(
                ref("country"), ref("customer"), ref("order_header"), ref("order_line"));
        assertThat(order.hasDeferredEdges()).isFalse();
        assertThat(order.maxDepth()).isEqualTo(3);
    }

    @Test
    void ordersADiamondWithBothBranchesBeforeTheJoin() {
        SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                table("account"),
                table("invoice").references("account_id", "account", false),
                table("payment").references("account_id", "account", false),
                table("allocation")
                        .references("invoice_id", "invoice", false)
                        .references("payment_id", "payment", false));

        DependencyGraph graph = DependencyGraph.of(snapshot);
        SeedOrder order = graph.order();

        assertParentsPrecedeChildren(graph, order);
        assertThat(order.order()).hasSize(4);
        assertThat(order.order().getFirst()).isEqualTo(ref("account"));
        assertThat(order.order().getLast()).isEqualTo(ref("allocation"));
        assertThat(order.depth()).containsEntry(ref("allocation"), 2);
    }

    @Test
    void producesTheSameOrderEveryTime() {
        SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                table("a"),
                table("b").references("a_id", "a", false),
                table("c").references("a_id", "a", false),
                table("d").references("b_id", "b", false).references("c_id", "c", false),
                table("e").references("a_id", "a", false));

        List<TableRef> first = DependencyGraph.of(snapshot).order().order();
        for (int i = 0; i < 25; i++) {
            assertThat(DependencyGraph.of(snapshot).order().order())
                    .as("ordering must be stable across runs, otherwise a seeded dataset is not reproducible")
                    .isEqualTo(first);
        }
    }

    @Test
    void aSelfReferenceIsNotAnOrderingConstraint() {
        SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                table("employee").references("manager_id", "employee", true));

        DependencyGraph graph = DependencyGraph.of(snapshot);
        SeedOrder order = graph.order();

        assertThat(order.order()).containsExactly(ref("employee"));
        assertThat(graph.selfEdges()).hasSize(1);
        assertThat(graph.edges()).isEmpty();
        assertThat(order.notes()).anyMatch(note -> note.contains("Self-reference"));
    }

    @Test
    void breaksATwoTableCycleAtItsNullableEdge() {
        // customer.primary_order_id is nullable; order.customer_id is not.
        SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                table("customer").references("primary_order_id", "order_header", true),
                table("order_header").references("customer_id", "customer", false));

        DependencyGraph graph = DependencyGraph.of(snapshot);
        SeedOrder order = graph.order();

        assertThat(order.order()).containsExactly(ref("customer"), ref("order_header"));
        assertThat(order.deferredEdges()).singleElement()
                .satisfies(fk -> assertThat(fk.name()).isEqualTo("fk_customer_primary_order_id"));
        assertThat(order.cycles()).hasSize(1);
        assertThat(order.notes()).anyMatch(note -> note.contains("Broke a foreign-key cycle"));
        assertParentsPrecedeChildren(graph, order);
    }

    @Test
    void breaksALongerCycleAtTheOnlyNullableEdge() {
        SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                table("a").references("c_id", "c", true),
                table("b").references("a_id", "a", false),
                table("c").references("b_id", "b", false));

        DependencyGraph graph = DependencyGraph.of(snapshot);
        SeedOrder order = graph.order();

        assertThat(order.order()).containsExactly(ref("a"), ref("b"), ref("c"));
        assertThat(order.deferredEdges()).singleElement()
                .satisfies(fk -> assertThat(fk.child()).isEqualTo(ref("a")));
        assertParentsPrecedeChildren(graph, order);
    }

    @Test
    void refusesACycleInWhichEveryEdgeIsNotNull() {
        SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                table("left_side").references("right_id", "right_side", false),
                table("right_side").references("left_id", "left_side", false));

        assertThatThrownBy(() -> DependencyGraph.of(snapshot).order())
                .isInstanceOf(CyclicSchemaException.class)
                .hasMessageContaining("cannot be broken")
                .hasMessageContaining("NOT NULL");
    }

    @Test
    void reportsForeignKeysLeavingTheSelectedSubset() {
        SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                table("region"),
                table("store").references("region_id", "region", true),
                table("sale").references("store_id", "store", false));

        DependencyGraph graph = DependencyGraph.of(snapshot, Set.of(ref("store"), ref("sale")));
        SeedOrder order = graph.order();

        assertThat(order.order()).containsExactly(ref("store"), ref("sale"));
        assertThat(graph.danglingEdges()).singleElement()
                .satisfies(fk -> assertThat(fk.parent()).isEqualTo(ref("region")));
        assertThat(order.notes()).anyMatch(note -> note.contains("points outside the dataset"));
    }

    @Test
    void closureDragsInRequiredParentsTransitively() {
        SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                table("country"),
                table("customer").references("country_id", "country", false),
                table("order_header").references("customer_id", "customer", false),
                table("order_line").references("order_id", "order_header", false));

        Set<TableRef> closure = DependencyGraph.requiredClosure(
                snapshot, Set.of(ref("order_line")), Set.of());

        assertThat(closure).containsExactlyInAnyOrder(
                ref("order_line"), ref("order_header"), ref("customer"), ref("country"));
    }

    @Test
    void closureLeavesOptionalParentsOut() {
        SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                table("promo"),
                table("order_header").references("promo_id", "promo", true));

        Set<TableRef> closure = DependencyGraph.requiredClosure(
                snapshot, Set.of(ref("order_header")), Set.of());

        assertThat(closure).containsExactly(ref("order_header"));
    }

    @Test
    void closureNeverPullsBackATableTheRequestExcluded() {
        SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                table("tenant"),
                table("project").references("tenant_id", "tenant", false));

        Set<TableRef> closure = DependencyGraph.requiredClosure(
                snapshot, Set.of(ref("project")), Set.of(ref("tenant")));

        assertThat(closure).containsExactly(ref("project"));
    }

    @Test
    void exposesRootsLeavesAndInDegrees() {
        SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                table("catalog"),
                table("product").references("catalog_id", "catalog", false),
                table("variant").references("product_id", "product", false),
                table("review").references("product_id", "product", false));

        DependencyGraph graph = DependencyGraph.of(snapshot);

        assertThat(graph.roots()).containsExactly(ref("catalog"));
        assertThat(graph.leaves()).containsExactlyInAnyOrder(ref("variant"), ref("review"));
        assertThat(graph.inDegrees()).containsEntry(ref("product"), 2);
        assertThat(graph.edgeCount()).isEqualTo(3);
    }

    @Test
    void groupsTablesIntoLevelsThatCanBeSeededTogether() {
        SchemaSnapshot snapshot = SchemaFixtures.snapshot(
                table("a"),
                table("b"),
                table("c").references("a_id", "a", false),
                table("d").references("b_id", "b", false),
                table("e").references("c_id", "c", false));

        SeedOrder order = DependencyGraph.of(snapshot).order();

        assertThat(order.levels().get(0)).containsExactlyInAnyOrder(ref("a"), ref("b"));
        assertThat(order.levels().get(1)).containsExactlyInAnyOrder(ref("c"), ref("d"));
        assertThat(order.levels().get(2)).containsExactly(ref("e"));
    }

    @Test
    void handlesAWideSchemaWithoutLosingTheOrderingInvariant() {
        // A fan-in/fan-out shape wide enough that a naive ready-queue would
        // reorder between runs if it were not sorted.
        SchemaFixtures.Builder[] builders = new SchemaFixtures.Builder[30];
        builders[0] = table("t00");
        for (int i = 1; i < 30; i++) {
            String name = String.format("t%02d", i);
            builders[i] = table(name).references("p_id", String.format("t%02d", (i - 1) / 3), false);
        }

        SchemaSnapshot snapshot = SchemaFixtures.snapshot(builders);
        DependencyGraph graph = DependencyGraph.of(snapshot);
        SeedOrder order = graph.order();

        assertThat(order.order()).hasSize(30);
        assertParentsPrecedeChildren(graph, order);
    }
}
