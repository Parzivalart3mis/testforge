package com.helios.testforge.generate;

import com.helios.testforge.domain.schema.TableRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The keys already seeded, so a child row can point at a parent that exists.
 *
 * <p>This is where referential consistency actually comes from. Tables are
 * seeded in dependency order; as each one is generated its key values are
 * registered here, and any child that references it draws from the registered
 * values rather than inventing one. A generated foreign key therefore always
 * resolves, without the seeder needing to check.
 *
 * <p>Only the columns some child actually references are retained. On a wide
 * table at a large scale the difference is the whole dataset in memory versus a
 * few key columns — the pool is told up front which column sets matter and
 * discards everything else as rows stream past.
 */
public final class KeyPool {

    /** table -> referenced column set -> the values registered for it. */
    private final Map<TableRef, Map<List<String>, List<Object[]>>> pools = new HashMap<>();

    /** table -> the column sets worth retaining, computed from inbound foreign keys. */
    private final Map<TableRef, List<List<String>>> retained = new HashMap<>();

    /**
     * Declares which column sets of a table will be referenced. Anything not
     * declared is dropped on registration rather than accumulated.
     */
    public void retain(TableRef table, List<List<String>> columnSets) {
        List<List<String>> normalised = columnSets.stream().map(List::copyOf).distinct().toList();
        retained.put(table, normalised);
        Map<List<String>, List<Object[]>> tablePools = pools.computeIfAbsent(table, k -> new HashMap<>());
        normalised.forEach(columns -> tablePools.computeIfAbsent(columns, k -> new ArrayList<>()));
    }

    /**
     * Registers one seeded row's values.
     *
     * @param table  the table the row belongs to
     * @param row    the row's values, keyed by column name
     */
    public void register(TableRef table, Map<String, Object> row) {
        List<List<String>> columnSets = retained.get(table);
        if (columnSets == null || columnSets.isEmpty()) {
            return;
        }
        Map<List<String>, List<Object[]>> tablePools = pools.get(table);
        for (List<String> columns : columnSets) {
            Object[] values = new Object[columns.size()];
            boolean complete = true;
            for (int i = 0; i < columns.size(); i++) {
                Object value = row.get(columns.get(i));
                if (value == null) {
                    // A NULL key cannot satisfy a child reference, so it is not
                    // a usable parent and is left out of the pool.
                    complete = false;
                    break;
                }
                values[i] = value;
            }
            if (complete) {
                tablePools.get(columns).add(values);
            }
        }
    }

    /**
     * Draws a parent key uniformly.
     *
     * @return the referenced values in {@code columns} order, or null when no
     *         parent row is available
     */
    public Object[] draw(TableRef table, List<String> columns, SplittableRandom random) {
        List<Object[]> pool = poolFor(table, columns);
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }

    /**
     * Draws by index rather than randomly. Used for self-references, where the
     * value must come from a row already generated in this same table.
     */
    public Object[] drawBelow(TableRef table, List<String> columns, int exclusiveUpperBound,
                              SplittableRandom random) {
        List<Object[]> pool = poolFor(table, columns);
        if (pool == null) {
            return null;
        }
        int bound = Math.min(exclusiveUpperBound, pool.size());
        if (bound <= 0) {
            return null;
        }
        return pool.get(random.nextInt(bound));
    }

    public int size(TableRef table, List<String> columns) {
        List<Object[]> pool = poolFor(table, columns);
        return pool == null ? 0 : pool.size();
    }

    public boolean isEmpty(TableRef table, List<String> columns) {
        return size(table, columns) == 0;
    }

    /** Frees a table's retained keys once nothing references it any more. */
    public void release(TableRef table) {
        pools.remove(table);
        retained.remove(table);
    }

    /** Total retained key tuples, for reporting the pool's memory footprint. */
    public long retainedTuples() {
        return pools.values().stream()
                .flatMap(byColumns -> byColumns.values().stream())
                .mapToLong(List::size)
                .sum();
    }

    private List<Object[]> poolFor(TableRef table, List<String> columns) {
        Map<List<String>, List<Object[]>> tablePools = pools.get(table);
        return tablePools == null ? null : tablePools.get(List.copyOf(columns));
    }
}
