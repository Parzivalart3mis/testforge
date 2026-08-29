package com.helios.testforge.generate;

import com.helios.testforge.domain.plan.ColumnPlan;
import com.helios.testforge.domain.plan.ColumnRole;
import com.helios.testforge.domain.plan.GenerationPlan;
import com.helios.testforge.domain.plan.TablePlan;
import com.helios.testforge.domain.schema.ColumnMeta;
import com.helios.testforge.domain.schema.ForeignKey;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.domain.schema.TableMeta;
import com.helios.testforge.domain.schema.TableRef;
import com.helios.testforge.mask.MaskContext;
import com.helios.testforge.mask.MaskingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Generates rows for one table at a time, in seed order.
 *
 * <p>The generator is driven entirely by the plan and the {@link KeyPool}: it
 * never queries the target database to find a parent key, because by the time a
 * table is generated every table it depends on has already been generated and
 * registered. That is what makes a dataset referentially consistent by
 * construction rather than by validation afterwards.
 *
 * <p>Masking runs as a second, separate pass over every generated value. On a
 * purely synthetic dataset that is belt-and-braces — the generators already
 * emit reserved-range SSNs and example.com addresses — but it is deliberate:
 * the guarantee the platform makes is that <em>no column classified sensitive
 * ever reaches a seeded database unmasked</em>, and enforcing that at the exit
 * of the pipeline keeps it true regardless of which generator produced the
 * value, or of a future mode that subsets real rows instead of inventing them.
 */
@Component
public class DatasetGenerator {

    private static final Logger log = LoggerFactory.getLogger(DatasetGenerator.class);

    /**
     * Deterministic retries allowed when a composite key collides. Bounded so a
     * near-saturated key space fails loudly instead of looping; the planner caps
     * junction tables well below saturation so this is rarely approached.
     */
    private static final int MAX_KEY_RETRIES = 64;

    private final GeneratorResolver resolver;
    private final MaskingEngine maskingEngine;

    public DatasetGenerator(GeneratorResolver resolver, MaskingEngine maskingEngine) {
        this.resolver = resolver;
        this.maskingEngine = maskingEngine;
    }

    /**
     * Prepares a pool sized for the plan, declaring which key columns each table
     * must retain.
     */
    public KeyPool newKeyPool(SchemaSnapshot snapshot, GenerationPlan plan) {
        KeyPool pool = new KeyPool();
        Map<TableRef, Set<List<String>>> referenced = new HashMap<>();

        for (TablePlan tablePlan : plan.tables()) {
            TableMeta table = snapshot.requireTable(tablePlan.table());
            for (ForeignKey fk : table.foreignKeys()) {
                referenced.computeIfAbsent(fk.parent(), k -> new HashSet<>()).add(fk.parentColumns());
            }
        }
        for (TablePlan tablePlan : plan.tables()) {
            Set<List<String>> columnSets = referenced.getOrDefault(tablePlan.table(), Set.of());
            if (!columnSets.isEmpty()) {
                pool.retain(tablePlan.table(), List.copyOf(columnSets));
            }
        }
        return pool;
    }

    /**
     * Generates every row for one table.
     *
     * @param snapshot  the schema
     * @param plan      the whole plan, for the dataset seed
     * @param tablePlan the table to generate
     * @param pool      keys of already-generated tables; this table's keys are registered into it
     * @param datasetId salt scoping masked values to this dataset
     * @return rows as column-name to value maps, in generation order
     */
    public List<Map<String, Object>> generateTable(SchemaSnapshot snapshot,
                                                   GenerationPlan plan,
                                                   TablePlan tablePlan,
                                                   KeyPool pool,
                                                   String datasetId) {
        TableMeta table = snapshot.requireTable(tablePlan.table());
        String qualified = table.qualified();
        long seed = plan.seed();

        Map<String, ValueGenerator> generators = new HashMap<>();
        Map<String, ColumnMeta> columns = table.columnsByName();
        for (ColumnPlan columnPlan : tablePlan.columns()) {
            ColumnMeta column = columns.get(columnPlan.column());
            if (column != null && needsGenerator(columnPlan.role())) {
                generators.put(columnPlan.column(), resolver.resolve(table, column));
            }
        }

        MaskContext baseContext = MaskContext.forDataset(datasetId);
        List<Map<String, Object>> rows = new ArrayList<>(tablePlan.rowCount());
        Set<List<Object>> seenKeys = new HashSet<>();
        List<String> keyColumns = table.primaryKeyOpt().map(pk -> pk.columns()).orElse(List.of());

        int collisions = 0;
        for (int rowIndex = 0; rowIndex < tablePlan.rowCount(); rowIndex++) {
            Map<String, Object> row = null;

            for (int attempt = 0; attempt < MAX_KEY_RETRIES; attempt++) {
                row = generateRow(table, tablePlan, generators, pool, seed, qualified,
                        rowIndex, attempt, baseContext);
                if (keyColumns.isEmpty()) {
                    break;
                }
                List<Object> key = keyColumns.stream().map(row::get).toList();
                if (key.contains(null) || seenKeys.add(key)) {
                    break;
                }
                collisions++;
                row = null;
            }

            if (row == null) {
                log.warn("Skipping row {} of {}: no unique primary key found in {} deterministic attempts",
                        rowIndex, qualified, MAX_KEY_RETRIES);
                continue;
            }
            rows.add(row);
            pool.register(table.ref(), row);
        }

        if (collisions > 0) {
            log.debug("{}: resolved {} primary key collisions by deterministic retry", qualified, collisions);
        }
        return rows;
    }

    // ---------------------------------------------------------------- a row

    private Map<String, Object> generateRow(TableMeta table,
                                            TablePlan tablePlan,
                                            Map<String, ValueGenerator> generators,
                                            KeyPool pool,
                                            long seed,
                                            String qualified,
                                            int rowIndex,
                                            int attempt,
                                            MaskContext baseContext) {
        Map<String, Object> row = new LinkedHashMap<>();
        // The row key scopes correlated masking — every date in this row shifts together.
        MaskContext rowContext = baseContext.withRow(qualified + "#" + rowIndex);

        // Foreign keys first: a composite key made of foreign keys needs its
        // parent values in place before the primary key can be assembled.
        for (ForeignKey fk : table.foreignKeys()) {
            applyForeignKey(table, fk, tablePlan, pool, seed, qualified, rowIndex, attempt, row);
        }

        for (ColumnPlan columnPlan : tablePlan.columns()) {
            String name = columnPlan.column();
            if (row.containsKey(name) || columnPlan.role() == ColumnRole.DATABASE_GENERATED) {
                continue;
            }

            SplittableRandom random = new SplittableRandom(
                    RowSeed.forCell(seed, qualified, name, rowIndex) + attempt * 0x9E3779B9L);

            Object value = switch (columnPlan.role()) {
                case PRIMARY_KEY -> primaryKeyValue(table, name, rowIndex, attempt, random);
                case DEFERRED_FOREIGN_KEY, DANGLING_FOREIGN_KEY -> null;
                default -> {
                    ValueGenerator generator = generators.get(name);
                    yield generator == null ? null : generator.generate(random, rowIndex);
                }
            };

            if (value != null && columnPlan.isMasked()) {
                value = maskingEngine.mask(columnPlan.mask(), value, rowContext, null);
            }
            row.put(name, value);
        }
        return row;
    }

    private void applyForeignKey(TableMeta table, ForeignKey fk, TablePlan tablePlan, KeyPool pool,
                                 long seed, String qualified, int rowIndex, int attempt,
                                 Map<String, Object> row) {
        ColumnRole role = roleForForeignKey(fk, tablePlan);
        if (role == ColumnRole.DEFERRED_FOREIGN_KEY || role == ColumnRole.DANGLING_FOREIGN_KEY) {
            fk.childColumns().forEach(column -> row.put(column, null));
            return;
        }

        SplittableRandom random = new SplittableRandom(
                RowSeed.forCell(seed, qualified, "fk:" + fk.name(), rowIndex) + attempt * 0x9E3779B9L);

        Object[] parentKey;
        if (fk.isSelfReference()) {
            // Point at an already-generated row, or at nothing for the first rows.
            parentKey = rowIndex == 0 ? null : pool.drawBelow(fk.parent(), fk.parentColumns(), rowIndex, random);
            if (parentKey != null && random.nextInt(100) < 20) {
                parentKey = null;
            }
        } else {
            parentKey = pool.draw(fk.parent(), fk.parentColumns(), random);
        }

        for (int i = 0; i < fk.childColumns().size(); i++) {
            row.put(fk.childColumns().get(i), parentKey == null ? null : parentKey[i]);
        }
    }

    private ColumnRole roleForForeignKey(ForeignKey fk, TablePlan tablePlan) {
        return tablePlan.columns().stream()
                .filter(c -> c.column().equals(fk.childColumns().getFirst()))
                .map(ColumnPlan::role)
                .findFirst()
                .orElse(ColumnRole.FOREIGN_KEY);
    }

    /**
     * Assigns a primary key value. Integer keys count from one so a seeded
     * database reads naturally and is trivially comparable across runs; UUID and
     * text keys are derived from the cell's stream, which keeps them reproducible.
     */
    private Object primaryKeyValue(TableMeta table, String column, int rowIndex,
                                   int attempt, SplittableRandom random) {
        ColumnMeta meta = table.requireColumn(column);
        String type = com.helios.testforge.introspect.TypeMod.base(meta.udtName());
        int ordinal = rowIndex + 1 + attempt * 1_000_000;

        return switch (type) {
            case "int2" -> (short) ordinal;
            case "int4" -> ordinal;
            case "int8" -> (long) ordinal;
            case "uuid" -> deterministicUuid(random);
            default -> resolver.resolve(table, meta).generate(random, rowIndex);
        };
    }

    private UUID deterministicUuid(SplittableRandom random) {
        long high = (random.nextLong() & ~0xF000L) | 0x4000L;
        long low = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(high, low);
    }

    private boolean needsGenerator(ColumnRole role) {
        return role == ColumnRole.VALUE || role == ColumnRole.PRIMARY_KEY;
    }

    /**
     * Values for the second pass that fills a cycle-breaking foreign key.
     *
     * @return for each seeded row of the table, the parent key to set, keyed by child column
     */
    public List<Map<String, Object>> generateDeferredValues(TableRef table,
                                                            ForeignKey fk,
                                                            int rowCount,
                                                            KeyPool pool,
                                                            long seed) {
        List<Map<String, Object>> updates = new ArrayList<>(rowCount);
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            SplittableRandom random = new SplittableRandom(
                    RowSeed.forCell(seed, table.qualified(), "deferred:" + fk.name(), rowIndex));
            Object[] parentKey = pool.draw(fk.parent(), fk.parentColumns(), random);

            Map<String, Object> update = new LinkedHashMap<>();
            for (int i = 0; i < fk.childColumns().size(); i++) {
                update.put(fk.childColumns().get(i), parentKey == null ? null : parentKey[i]);
            }
            updates.add(update);
        }
        return updates;
    }
}
