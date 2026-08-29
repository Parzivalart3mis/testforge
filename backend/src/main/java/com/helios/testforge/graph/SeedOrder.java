package com.helios.testforge.graph;

import com.helios.testforge.domain.schema.ForeignKey;
import com.helios.testforge.domain.schema.TableRef;

import java.util.List;
import java.util.Map;

/**
 * The order tables must be seeded in, plus what had to be relaxed to get there.
 *
 * @param order          tables in dependency order — every table appears after all of its parents
 * @param deferredEdges  foreign keys removed from the ordering to break a cycle; the seeder inserts
 *                       these columns as NULL and fills them in a second UPDATE pass
 * @param danglingEdges  foreign keys pointing at tables outside the dataset; seeded as NULL
 * @param depth          each table's distance from the nearest root, for display
 * @param cycles         cycles that were detected, whether or not they were broken
 * @param notes          human-readable explanations of every relaxation, surfaced in the plan
 */
public record SeedOrder(
        List<TableRef> order,
        List<ForeignKey> deferredEdges,
        List<ForeignKey> danglingEdges,
        Map<TableRef, Integer> depth,
        List<List<TableRef>> cycles,
        List<String> notes) {

    public SeedOrder {
        order = List.copyOf(order);
        deferredEdges = List.copyOf(deferredEdges);
        danglingEdges = List.copyOf(danglingEdges);
        depth = Map.copyOf(depth);
        cycles = cycles.stream().map(List::copyOf).toList();
        notes = List.copyOf(notes);
    }

    public int size() {
        return order.size();
    }

    /** Whether the ordering required breaking a cycle. */
    public boolean hasDeferredEdges() {
        return !deferredEdges.isEmpty();
    }

    /** Whether a given foreign key was deferred rather than satisfied inline. */
    public boolean isDeferred(ForeignKey foreignKey) {
        return deferredEdges.stream().anyMatch(fk -> fk.name().equals(foreignKey.name())
                && fk.child().equals(foreignKey.child()));
    }

    /** Whether a given foreign key points outside the dataset and must be seeded NULL. */
    public boolean isDangling(ForeignKey foreignKey) {
        return danglingEdges.stream().anyMatch(fk -> fk.name().equals(foreignKey.name())
                && fk.child().equals(foreignKey.child()));
    }

    /** The deepest chain in the graph, i.e. how many dependency levels the dataset has. */
    public int maxDepth() {
        return depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /** Tables grouped by depth. Everything within one level can be seeded in parallel. */
    public Map<Integer, List<TableRef>> levels() {
        return order.stream().collect(java.util.stream.Collectors.groupingBy(
                t -> depth.getOrDefault(t, 0),
                java.util.TreeMap::new,
                java.util.stream.Collectors.toList()));
    }
}
