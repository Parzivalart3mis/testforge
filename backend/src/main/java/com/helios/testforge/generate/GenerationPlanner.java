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
import com.helios.testforge.domain.schema.ColumnMeta;
import com.helios.testforge.domain.schema.ForeignKey;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.domain.schema.TableMeta;
import com.helios.testforge.domain.schema.TableRef;
import com.helios.testforge.graph.DependencyGraph;
import com.helios.testforge.graph.SeedOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a request plus a schema into a plan, without touching a database.
 *
 * <p>Everything that can be decided up front is decided here: which tables are
 * in, how many rows each gets, what supplies every column's value, and which
 * columns get masked and why. The result is returned to the requester for
 * review before provisioning starts, so a mistake — a table nobody meant to
 * include, a sensitive column left unmasked — is caught while it still costs
 * nothing.
 */
@Component
public class GenerationPlanner {

    private static final Logger log = LoggerFactory.getLogger(GenerationPlanner.class);

    private final GeneratorResolver resolver;
    private final TestForgeProperties properties;

    public GenerationPlanner(GeneratorResolver resolver, TestForgeProperties properties) {
        this.resolver = resolver;
        this.properties = properties;
    }

    /**
     * Builds the plan.
     *
     * @param snapshot the introspected schema
     * @param request  what the engineer asked for
     * @param seed     the resolved dataset seed
     */
    public GenerationPlan plan(SchemaSnapshot snapshot, DatasetRequest request, long seed) {
        List<String> warnings = new ArrayList<>();

        Set<TableRef> selected = selectTables(snapshot, request, warnings);
        DependencyGraph graph = DependencyGraph.of(snapshot, selected);
        SeedOrder order = graph.order();
        warnings.addAll(order.notes());

        Map<TableRef, Integer> rowCounts = planRowCounts(snapshot, request, order, graph, seed, warnings);

        List<TablePlan> tablePlans = new ArrayList<>(order.size());
        long totalRows = 0;
        int maskedColumns = 0;

        for (int position = 0; position < order.order().size(); position++) {
            TableRef ref = order.order().get(position);
            TableMeta table = snapshot.requireTable(ref);
            int rows = rowCounts.getOrDefault(ref, request.scale());

            List<ColumnPlan> columnPlans = planColumns(table, order, request.masking(), warnings);
            maskedColumns += (int) columnPlans.stream().filter(ColumnPlan::isMasked).count();
            totalRows += rows;

            tablePlans.add(new TablePlan(
                    ref,
                    position,
                    order.depth().getOrDefault(ref, 0),
                    rows,
                    columnPlans,
                    table.foreignKeys().stream().filter(order::isDeferred).toList(),
                    table.selfReferences()));
        }

        long maxTotal = properties.generation().maxTotalRows();
        if (totalRows > maxTotal) {
            throw new IllegalArgumentException("plan would generate " + totalRows
                    + " rows, above the configured ceiling of " + maxTotal
                    + ". Lower the scale, or narrow the table selection.");
        }

        log.info("Planned {} tables, {} rows, {} masked columns (seed {})",
                tablePlans.size(), totalRows, maskedColumns, seed);

        return new GenerationPlan(seed, snapshot.schema(), snapshot.fingerprint(),
                tablePlans, totalRows, maskedColumns, warnings);
    }

    // ------------------------------------------------------- table selection

    /**
     * Resolves the requested table set, expanding it with required parents.
     * Excludes are applied last and win, so an exclusion is never quietly undone
     * by the closure.
     */
    private Set<TableRef> selectTables(SchemaSnapshot snapshot, DatasetRequest request, List<String> warnings) {
        Set<TableRef> all = new LinkedHashSet<>(snapshot.tables().stream().map(TableMeta::ref).toList());

        Set<TableRef> excluded = new TreeSet<>();
        for (String name : request.excludeTables()) {
            excluded.add(TableRef.parse(name, snapshot.schema()));
        }

        Set<TableRef> requested;
        if (request.includeTables().isEmpty()) {
            requested = new TreeSet<>(all);
        } else {
            requested = new TreeSet<>();
            for (String name : request.includeTables()) {
                TableRef ref = TableRef.parse(name, snapshot.schema());
                if (!all.contains(ref)) {
                    throw new IllegalArgumentException("requested table " + ref.qualified()
                            + " does not exist in schema " + snapshot.schema());
                }
                requested.add(ref);
            }
        }
        requested.removeAll(excluded);

        Set<TableRef> closure = DependencyGraph.requiredClosure(snapshot, requested, excluded);
        Set<TableRef> added = new TreeSet<>(closure);
        added.removeAll(requested);
        if (!added.isEmpty()) {
            warnings.add("Added " + added.size() + " table(s) the selection depends on through NOT NULL "
                    + "foreign keys: " + added.stream().map(TableRef::qualified).toList()
                    + ". Without them the requested rows cannot be inserted.");
        }

        if (closure.isEmpty()) {
            throw new IllegalArgumentException("the request selects no tables");
        }
        return closure;
    }

    // ---------------------------------------------------------- row counting

    /**
     * Sizes each table.
     *
     * <p>Root tables get the requested scale. A child is sized from its largest
     * parent multiplied by a fanout drawn deterministically from the table's own
     * seed, which produces the uneven, realistic shape of a real dataset rather
     * than every table having identical row counts. Explicit overrides always win.
     *
     * <p>Junction tables — those whose primary key is made entirely of foreign
     * key columns — are additionally capped at the number of distinct parent
     * combinations that actually exist, since no more unique rows are possible.
     */
    private Map<TableRef, Integer> planRowCounts(SchemaSnapshot snapshot,
                                                 DatasetRequest request,
                                                 SeedOrder order,
                                                 DependencyGraph graph,
                                                 long seed,
                                                 List<String> warnings) {
        Map<TableRef, Integer> counts = new HashMap<>();
        Map<String, Integer> overrides = normaliseOverrides(request.rowOverrides(), snapshot.schema());
        int maxPerTable = properties.generation().maxRowsPerTable();
        int fanoutMin = properties.generation().fanoutMin();
        int fanoutMax = properties.generation().fanoutMax();

        for (TableRef ref : order.order()) {
            TableMeta table = snapshot.requireTable(ref);

            Integer override = overrides.get(ref.qualified());
            if (override == null) {
                override = overrides.get(ref.name());
            }
            if (override != null) {
                counts.put(ref, clamp(override, maxPerTable, ref, warnings));
                continue;
            }

            Set<TableRef> parents = graph.parentsOf(ref);
            int rows;
            if (parents.isEmpty()) {
                rows = request.scale();
            } else {
                int largestParent = parents.stream()
                        .mapToInt(parent -> counts.getOrDefault(parent, request.scale()))
                        .max()
                        .orElse(request.scale());
                int span = Math.max(1, fanoutMax - fanoutMin + 1);
                int fanout = fanoutMin + (int) Math.floorMod(RowSeed.forTable(seed, ref.qualified()), span);
                rows = Math.max(1, largestParent * fanout);
            }

            rows = capJunctionTable(table, graph, counts, rows, warnings);
            rows = capToUniqueCapacity(table, rows, warnings);
            counts.put(ref, clamp(rows, maxPerTable, ref, warnings));
        }
        return counts;
    }

    /**
     * A table whose whole primary key is foreign key columns can hold at most
     * the product of its parents' row counts; asking for more guarantees a
     * primary key collision the generator could never resolve.
     */
    private int capJunctionTable(TableMeta table, DependencyGraph graph,
                                 Map<TableRef, Integer> counts, int requested, List<String> warnings) {
        var primaryKey = table.primaryKeyOpt().orElse(null);
        if (primaryKey == null || !primaryKey.isComposite()) {
            return requested;
        }
        Set<String> fkColumns = new LinkedHashSet<>();
        table.foreignKeys().forEach(fk -> fkColumns.addAll(fk.childColumns()));
        if (!fkColumns.containsAll(primaryKey.columns())) {
            return requested;
        }

        long combinations = 1;
        for (TableRef parent : graph.parentsOf(table.ref())) {
            combinations *= Math.max(1, counts.getOrDefault(parent, 1));
            if (combinations > Integer.MAX_VALUE) {
                return requested;
            }
        }
        // Leave headroom: filling the space exactly would make the last few
        // rows take many deterministic retries to find an unused combination.
        int capped = (int) Math.max(1, combinations * 3 / 4);
        if (capped < requested) {
            warnings.add("Capped " + table.qualified() + " at " + capped + " rows: its primary key is made "
                    + "entirely of foreign keys, so it cannot hold more distinct rows than its parents allow.");
            return capped;
        }
        return requested;
    }

    /**
     * Caps a table at the number of distinct values its narrowest unique column
     * can actually hold.
     *
     * <p>A {@code char(2) UNIQUE} country code has room for 1296 distinct
     * values however many rows are asked for. Discovering that as a constraint
     * violation halfway through seeding wastes the whole run, so it is decided
     * here where it costs nothing and can be reported.
     */
    private int capToUniqueCapacity(TableMeta table, int requested, List<String> warnings) {
        long capacity = Long.MAX_VALUE;
        String limiting = null;

        for (var unique : table.uniques()) {
            if (unique.isComposite()) {
                continue;
            }
            ColumnMeta column = table.requireColumn(unique.columns().getFirst());
            long columnCapacity = Generators.uniqueCapacity(
                    com.helios.testforge.introspect.TypeMod.base(column.udtName()), column.maxLength());
            if (columnCapacity < capacity) {
                capacity = columnCapacity;
                limiting = column.name();
            }
        }

        if (capacity < requested) {
            int capped = (int) capacity;
            warnings.add("Capped " + table.qualified() + " at " + capped + " rows: its unique column "
                    + limiting + " cannot hold more distinct values than that.");
            return capped;
        }
        return requested;
    }

    private int clamp(int rows, int maxPerTable, TableRef ref, List<String> warnings) {
        if (rows > maxPerTable) {
            warnings.add("Capped " + ref.qualified() + " at the configured per-table maximum of " + maxPerTable + ".");
            return maxPerTable;
        }
        return Math.max(0, rows);
    }

    private Map<String, Integer> normaliseOverrides(Map<String, Integer> overrides, String schema) {
        Map<String, Integer> normalised = new HashMap<>();
        overrides.forEach((name, count) -> {
            normalised.put(name, count);
            normalised.put(TableRef.parse(name, schema).qualified(), count);
        });
        return normalised;
    }

    // ------------------------------------------------------- column planning

    private List<ColumnPlan> planColumns(TableMeta table, SeedOrder order,
                                         MaskingPolicy policy, List<String> warnings) {
        List<ColumnPlan> plans = new ArrayList<>(table.columns().size());

        for (ColumnMeta column : table.columns()) {
            ColumnRole role = roleOf(table, column, order);
            ForeignKey owningFk = table.foreignKeyFor(column.name()).orElse(null);

            MaskStrategy strategy = policy.strategyFor(
                    table.qualified(), column.name(), column.dataClass());
            String maskSource = describeMaskSource(policy, table, column, strategy);

            if (strategy == MaskStrategy.NULLIFY && !column.nullable()) {
                warnings.add("Masking rule asks to NULL " + table.qualified() + "." + column.name()
                        + ", which is NOT NULL; falling back to a keyed hash.");
                strategy = MaskStrategy.HASH;
            }
            // Masking a key would break exactly the referential integrity the
            // platform exists to guarantee, so keys are never masked.
            if (role != ColumnRole.VALUE && strategy != MaskStrategy.PRESERVE) {
                if (role == ColumnRole.PRIMARY_KEY || role == ColumnRole.FOREIGN_KEY) {
                    warnings.add("Ignoring masking on " + table.qualified() + "." + column.name()
                            + ": it is a key column, and masking it would break referential integrity.");
                }
                strategy = MaskStrategy.PRESERVE;
                maskSource = "key column";
            }

            plans.add(new ColumnPlan(
                    column.name(),
                    column.formattedType(),
                    column.dataClass(),
                    role,
                    resolver.describe(table, column),
                    strategy,
                    maskSource,
                    table.isUniqueColumn(column.name()),
                    column.nullable(),
                    owningFk == null ? null : owningFk.parent().qualified(),
                    owningFk == null ? null : parentColumnFor(owningFk, column.name())));
        }
        return plans;
    }

    private ColumnRole roleOf(TableMeta table, ColumnMeta column, SeedOrder order) {
        if (column.databaseSupplied()) {
            return ColumnRole.DATABASE_GENERATED;
        }
        ForeignKey fk = table.foreignKeyFor(column.name()).orElse(null);
        if (fk != null) {
            if (fk.isSelfReference()) {
                return ColumnRole.SELF_REFERENCE;
            }
            if (order.isDeferred(fk)) {
                return ColumnRole.DEFERRED_FOREIGN_KEY;
            }
            if (order.isDangling(fk)) {
                return ColumnRole.DANGLING_FOREIGN_KEY;
            }
            return ColumnRole.FOREIGN_KEY;
        }
        if (table.primaryKeyOpt().map(pk -> pk.covers(column.name())).orElse(false)) {
            return ColumnRole.PRIMARY_KEY;
        }
        return ColumnRole.VALUE;
    }

    private String describeMaskSource(MaskingPolicy policy, TableMeta table,
                                      ColumnMeta column, MaskStrategy strategy) {
        MaskingRule rule = policy.matchingRule(table.qualified(), column.name());
        if (rule != null) {
            return "rule " + rule.tablePattern() + "." + rule.columnPattern();
        }
        if (strategy == MaskStrategy.PRESERVE) {
            return "not sensitive";
        }
        return "sensitive class " + column.dataClass();
    }

    private String parentColumnFor(ForeignKey fk, String childColumn) {
        int index = fk.childColumns().indexOf(childColumn);
        return index < 0 ? null : fk.parentColumns().get(index);
    }
}
