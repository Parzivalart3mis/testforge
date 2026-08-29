package com.helios.testforge.graph;

import com.helios.testforge.domain.schema.ColumnMeta;
import com.helios.testforge.domain.schema.ForeignKey;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.domain.schema.TableMeta;
import com.helios.testforge.domain.schema.TableRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The foreign-key graph of a dataset, and the ordering algorithm over it.
 *
 * <p>Nodes are tables; a directed edge runs child → parent for every foreign
 * key, meaning "the parent must exist first". Seeding order is a topological
 * sort of that graph.
 *
 * <p>Real schemas do not hand you a clean DAG, so three cases are handled
 * explicitly rather than being allowed to fail at INSERT time:
 *
 * <ul>
 *   <li><b>Self-references</b> ({@code employee.manager_id → employee.id}) are
 *       not ordering problems at all. Rows within a table are generated in
 *       index order, so a self-reference can point at an earlier row of the
 *       same table. These edges are excluded from the graph entirely.</li>
 *   <li><b>Cycles</b> ({@code customer.primary_order_id → order},
 *       {@code order.customer_id → customer}) are broken by deferring one
 *       nullable edge: those columns are seeded NULL and filled by an UPDATE
 *       pass once both tables exist. A cycle in which every edge is NOT NULL is
 *       genuinely unsatisfiable and raises {@link CyclicSchemaException}.</li>
 *   <li><b>Dangling edges</b> — foreign keys to tables the request excluded —
 *       are seeded NULL when nullable, and force the parent table back into the
 *       dataset when not.</li>
 * </ul>
 *
 * <p>Every tie is broken by table name so the same schema always produces the
 * same order. That matters more than it looks: a dataset's reproducibility
 * guarantee rests on row {@code n} of table {@code t} being generated from the
 * same seed every time, which requires the traversal itself to be stable.
 */
public final class DependencyGraph {

    private final Map<TableRef, TableMeta> tables;
    private final Map<TableRef, Set<TableRef>> parents;
    private final Map<TableRef, Set<TableRef>> children;
    private final List<ForeignKey> edges;
    private final List<ForeignKey> danglingEdges;
    private final List<ForeignKey> selfEdges;

    private DependencyGraph(Map<TableRef, TableMeta> tables,
                            Map<TableRef, Set<TableRef>> parents,
                            Map<TableRef, Set<TableRef>> children,
                            List<ForeignKey> edges,
                            List<ForeignKey> danglingEdges,
                            List<ForeignKey> selfEdges) {
        this.tables = tables;
        this.parents = parents;
        this.children = children;
        this.edges = edges;
        this.danglingEdges = danglingEdges;
        this.selfEdges = selfEdges;
    }

    /** Builds the graph over every table in the snapshot. */
    public static DependencyGraph of(SchemaSnapshot snapshot) {
        return of(snapshot, snapshot.tables().stream().map(TableMeta::ref).collect(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }

    /**
     * Builds the graph over a subset of the snapshot.
     *
     * @param included the tables the dataset covers; foreign keys leaving this
     *                 set become dangling edges
     */
    public static DependencyGraph of(SchemaSnapshot snapshot, Set<TableRef> included) {
        Map<TableRef, TableMeta> tables = new LinkedHashMap<>();
        for (TableMeta table : snapshot.tables()) {
            if (included.contains(table.ref())) {
                tables.put(table.ref(), table);
            }
        }

        Map<TableRef, Set<TableRef>> parents = new HashMap<>();
        Map<TableRef, Set<TableRef>> children = new HashMap<>();
        tables.keySet().forEach(ref -> {
            parents.put(ref, new TreeSet<>());
            children.put(ref, new TreeSet<>());
        });

        List<ForeignKey> edges = new ArrayList<>();
        List<ForeignKey> dangling = new ArrayList<>();
        List<ForeignKey> self = new ArrayList<>();

        for (TableMeta table : tables.values()) {
            for (ForeignKey fk : table.foreignKeys()) {
                if (fk.isSelfReference()) {
                    self.add(fk);
                } else if (!tables.containsKey(fk.parent())) {
                    dangling.add(fk);
                } else {
                    edges.add(fk);
                    parents.get(fk.child()).add(fk.parent());
                    children.get(fk.parent()).add(fk.child());
                }
            }
        }

        return new DependencyGraph(tables, parents, children, List.copyOf(edges),
                List.copyOf(dangling), List.copyOf(self));
    }

    /**
     * Expands a requested table set with every table it transitively depends on
     * through a NOT NULL foreign key.
     *
     * <p>Asking for {@code order_item} alone is almost never what someone means:
     * the rows cannot exist without their orders, and the orders cannot exist
     * without their customers. Pulling required parents in makes the subset
     * actually loadable, and the expansion is reported so nobody is surprised by
     * tables they did not ask for.
     *
     * @param snapshot  the full schema
     * @param requested the tables explicitly asked for
     * @param forbidden tables the request excluded, which are never pulled back in
     * @return the closure, in stable order
     */
    public static Set<TableRef> requiredClosure(SchemaSnapshot snapshot,
                                                Set<TableRef> requested,
                                                Set<TableRef> forbidden) {
        Map<TableRef, TableMeta> byRef = snapshot.byRef();
        Set<TableRef> closure = new TreeSet<>(requested);
        Deque<TableRef> pending = new ArrayDeque<>(new TreeSet<>(requested));

        while (!pending.isEmpty()) {
            TableMeta table = byRef.get(pending.poll());
            if (table == null) {
                continue;
            }
            for (ForeignKey fk : table.dependencyEdges()) {
                if (closure.contains(fk.parent()) || forbidden.contains(fk.parent())) {
                    continue;
                }
                boolean required = fk.childColumns().stream()
                        .map(table::requireColumn)
                        .anyMatch(column -> !column.nullable());
                if (required && byRef.containsKey(fk.parent())) {
                    closure.add(fk.parent());
                    pending.add(fk.parent());
                }
            }
        }
        return closure;
    }

    // ------------------------------------------------------------- ordering

    /**
     * Topologically orders the graph, breaking cycles where it legally can.
     *
     * <p>Kahn's algorithm with a sorted ready-queue: repeatedly emit every table
     * whose parents have all been emitted. When the queue empties with tables
     * left over, what remains is exactly the set of tables trapped in cycles;
     * those are decomposed into strongly connected components and one nullable
     * edge per component is deferred before resuming.
     *
     * @throws CyclicSchemaException when a cycle has no nullable edge to break
     */
    public SeedOrder order() {
        Map<TableRef, Set<TableRef>> remainingParents = new HashMap<>();
        parents.forEach((ref, ps) -> remainingParents.put(ref, new TreeSet<>(ps)));

        List<TableRef> ordered = new ArrayList<>(tables.size());
        Map<TableRef, Integer> depth = new HashMap<>();
        List<ForeignKey> deferred = new ArrayList<>();
        List<List<TableRef>> cycles = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        // Ready set is sorted, so a schema always orders identically.
        Set<TableRef> ready = new TreeSet<>();
        remainingParents.forEach((ref, ps) -> {
            if (ps.isEmpty()) {
                ready.add(ref);
            }
        });

        Set<TableRef> emitted = new HashSet<>();

        while (emitted.size() < tables.size()) {
            while (!ready.isEmpty()) {
                TableRef next = ready.iterator().next();
                ready.remove(next);
                if (!emitted.add(next)) {
                    continue;
                }
                ordered.add(next);
                depth.put(next, computeDepth(next, depth, deferred));

                for (TableRef child : children.getOrDefault(next, Set.of())) {
                    Set<TableRef> childParents = remainingParents.get(child);
                    if (childParents != null) {
                        childParents.remove(next);
                        if (childParents.isEmpty() && !emitted.contains(child)) {
                            ready.add(child);
                        }
                    }
                }
            }

            if (emitted.size() == tables.size()) {
                break;
            }

            // Whatever is left is in a cycle. Break the smallest component first
            // so the relaxation stays as local as possible.
            Set<TableRef> stuck = new TreeSet<>(tables.keySet());
            stuck.removeAll(emitted);
            List<List<TableRef>> components = stronglyConnectedComponents(stuck, remainingParents);
            List<TableRef> component = components.stream()
                    .filter(c -> c.size() > 1)
                    .min(Comparator.<List<TableRef>>comparingInt(List::size)
                            .thenComparing(c -> c.getFirst()))
                    .orElse(components.isEmpty() ? List.of() : components.getFirst());

            cycles.add(component);
            ForeignKey breakable = findBreakableEdge(component);
            if (breakable == null) {
                throw new CyclicSchemaException(component,
                        "every foreign key in the cycle is NOT NULL, so no row in any of these tables "
                                + "can be inserted first. Exclude one of the tables from the request, "
                                + "or make one of the referencing columns nullable.");
            }

            deferred.add(breakable);
            notes.add("Broke a foreign-key cycle by deferring " + breakable.name() + " (" + breakable.describe()
                    + "): those columns are seeded NULL and filled by a second pass after both tables exist.");

            Set<TableRef> childParents = remainingParents.get(breakable.child());
            if (childParents != null && childParents.remove(breakable.parent()) && childParents.isEmpty()) {
                ready.add(breakable.child());
            }
            if (ready.isEmpty()) {
                // The deferred edge was not the last blocker; re-seed the queue.
                remainingParents.forEach((ref, ps) -> {
                    if (ps.isEmpty() && !emitted.contains(ref)) {
                        ready.add(ref);
                    }
                });
                if (ready.isEmpty()) {
                    throw new CyclicSchemaException(component,
                            "deferring " + breakable.name() + " did not unblock the cycle");
                }
            }
        }

        for (ForeignKey fk : danglingEdges) {
            notes.add("Foreign key " + fk.name() + " (" + fk.describe()
                    + ") points outside the dataset; those columns are seeded NULL.");
        }
        for (ForeignKey fk : selfEdges) {
            notes.add("Self-reference " + fk.name() + " on " + fk.child().qualified()
                    + " is satisfied from earlier rows of the same table.");
        }

        return new SeedOrder(ordered, deferred, danglingEdges, depth, cycles, notes);
    }

    /** One more than the deepest parent, ignoring parents reached by a deferred edge. */
    private int computeDepth(TableRef ref, Map<TableRef, Integer> depth, List<ForeignKey> deferred) {
        int max = -1;
        for (TableRef parent : parents.getOrDefault(ref, Set.of())) {
            boolean viaDeferred = deferred.stream()
                    .anyMatch(fk -> fk.child().equals(ref) && fk.parent().equals(parent));
            if (viaDeferred) {
                continue;
            }
            Integer parentDepth = depth.get(parent);
            if (parentDepth != null) {
                max = Math.max(max, parentDepth);
            }
        }
        return max + 1;
    }

    /**
     * Picks the edge to sacrifice within a cycle: the one whose child columns are
     * all nullable, preferring the fewest columns and then the lowest constraint
     * name so the choice is deterministic.
     */
    private ForeignKey findBreakableEdge(List<TableRef> component) {
        Set<TableRef> inCycle = new HashSet<>(component);
        return edges.stream()
                .filter(fk -> inCycle.contains(fk.child()) && inCycle.contains(fk.parent()))
                .filter(this::isNullable)
                .min(Comparator.<ForeignKey>comparingInt(fk -> fk.childColumns().size())
                        .thenComparing(ForeignKey::name))
                .orElse(null);
    }

    /** An edge is breakable only when every one of its child columns accepts NULL. */
    private boolean isNullable(ForeignKey fk) {
        TableMeta child = tables.get(fk.child());
        if (child == null) {
            return false;
        }
        return fk.childColumns().stream()
                .map(child::column)
                .allMatch(column -> column.map(ColumnMeta::nullable).orElse(false));
    }

    /**
     * Tarjan's strongly connected components, restricted to the tables that are
     * still blocked. Each non-trivial component is a cycle that has to be broken.
     */
    private List<List<TableRef>> stronglyConnectedComponents(Set<TableRef> nodes,
                                                             Map<TableRef, Set<TableRef>> adjacency) {
        Map<TableRef, Integer> index = new HashMap<>();
        Map<TableRef, Integer> lowLink = new HashMap<>();
        Deque<TableRef> stack = new ArrayDeque<>();
        Set<TableRef> onStack = new HashSet<>();
        List<List<TableRef>> components = new ArrayList<>();
        int[] counter = {0};

        for (TableRef node : new TreeSet<>(nodes)) {
            if (!index.containsKey(node)) {
                strongConnect(node, nodes, adjacency, index, lowLink, stack, onStack, components, counter);
            }
        }
        return components;
    }

    /** Iterative Tarjan — a deep schema must not blow the JVM stack. */
    private void strongConnect(TableRef start,
                               Set<TableRef> nodes,
                               Map<TableRef, Set<TableRef>> adjacency,
                               Map<TableRef, Integer> index,
                               Map<TableRef, Integer> lowLink,
                               Deque<TableRef> stack,
                               Set<TableRef> onStack,
                               List<List<TableRef>> components,
                               int[] counter) {
        Deque<Frame> frames = new ArrayDeque<>();
        frames.push(new Frame(start, iteratorOf(start, nodes, adjacency)));
        index.put(start, counter[0]);
        lowLink.put(start, counter[0]);
        counter[0]++;
        stack.push(start);
        onStack.add(start);

        while (!frames.isEmpty()) {
            Frame frame = frames.peek();
            if (frame.neighbours.hasNext()) {
                TableRef neighbour = frame.neighbours.next();
                if (!nodes.contains(neighbour)) {
                    continue;
                }
                if (!index.containsKey(neighbour)) {
                    index.put(neighbour, counter[0]);
                    lowLink.put(neighbour, counter[0]);
                    counter[0]++;
                    stack.push(neighbour);
                    onStack.add(neighbour);
                    frames.push(new Frame(neighbour, iteratorOf(neighbour, nodes, adjacency)));
                } else if (onStack.contains(neighbour)) {
                    lowLink.put(frame.node, Math.min(lowLink.get(frame.node), index.get(neighbour)));
                }
            } else {
                frames.pop();
                if (!frames.isEmpty()) {
                    TableRef parent = frames.peek().node;
                    lowLink.put(parent, Math.min(lowLink.get(parent), lowLink.get(frame.node)));
                }
                if (lowLink.get(frame.node).equals(index.get(frame.node))) {
                    List<TableRef> component = new ArrayList<>();
                    TableRef member;
                    do {
                        member = stack.pop();
                        onStack.remove(member);
                        component.add(member);
                    } while (!member.equals(frame.node));
                    component.sort(Comparator.naturalOrder());
                    components.add(component);
                }
            }
        }
    }

    private java.util.Iterator<TableRef> iteratorOf(TableRef node, Set<TableRef> nodes,
                                                    Map<TableRef, Set<TableRef>> adjacency) {
        Set<TableRef> neighbours = new TreeSet<>(adjacency.getOrDefault(node, Set.of()));
        neighbours.retainAll(nodes);
        return neighbours.iterator();
    }

    private static final class Frame {
        final TableRef node;
        final java.util.Iterator<TableRef> neighbours;

        Frame(TableRef node, java.util.Iterator<TableRef> neighbours) {
            this.node = node;
            this.neighbours = neighbours;
        }
    }

    // -------------------------------------------------------------- queries

    public int tableCount() {
        return tables.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public Map<TableRef, TableMeta> tables() {
        return Map.copyOf(tables);
    }

    public List<ForeignKey> edges() {
        return edges;
    }

    public List<ForeignKey> danglingEdges() {
        return danglingEdges;
    }

    public List<ForeignKey> selfEdges() {
        return selfEdges;
    }

    /** Tables this one depends on. */
    public Set<TableRef> parentsOf(TableRef ref) {
        return Set.copyOf(parents.getOrDefault(ref, Set.of()));
    }

    /** Tables that depend on this one. */
    public Set<TableRef> childrenOf(TableRef ref) {
        return Set.copyOf(children.getOrDefault(ref, Set.of()));
    }

    /** Tables with no parents — the entry points of the graph. */
    public Set<TableRef> roots() {
        Set<TableRef> roots = new TreeSet<>();
        parents.forEach((ref, ps) -> {
            if (ps.isEmpty()) {
                roots.add(ref);
            }
        });
        return roots;
    }

    /** Tables nothing references — the leaves. */
    public Set<TableRef> leaves() {
        Set<TableRef> leaves = new TreeSet<>();
        children.forEach((ref, cs) -> {
            if (cs.isEmpty()) {
                leaves.add(ref);
            }
        });
        return leaves;
    }

    /** In-degree per table, i.e. how many distinct tables reference it. */
    public Map<TableRef, Integer> inDegrees() {
        Map<TableRef, Integer> degrees = new TreeMap<>();
        children.forEach((ref, cs) -> degrees.put(ref, cs.size()));
        return degrees;
    }
}
