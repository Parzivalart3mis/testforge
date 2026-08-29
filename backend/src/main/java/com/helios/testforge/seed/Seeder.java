package com.helios.testforge.seed;

import com.helios.testforge.config.TestForgeProperties;
import com.helios.testforge.domain.plan.ColumnPlan;
import com.helios.testforge.domain.plan.GenerationPlan;
import com.helios.testforge.domain.plan.TablePlan;
import com.helios.testforge.domain.schema.ColumnMeta;
import com.helios.testforge.domain.schema.ForeignKey;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.domain.schema.TableMeta;
import com.helios.testforge.generate.DatasetGenerator;
import com.helios.testforge.generate.KeyPool;
import com.helios.testforge.introspect.TypeMod;
import com.helios.testforge.util.Pg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Writes a generated dataset into an ephemeral database.
 *
 * <p>Tables are seeded in the plan's order, which the dependency graph
 * guarantees puts every parent before its children — so foreign keys are
 * satisfied at insert time and the database's own constraint checking is the
 * verification that the dataset is consistent. Nothing is disabled or deferred
 * to make the inserts succeed.
 *
 * <p>Three passes run in total, and the second and third exist for reasons the
 * first cannot handle:
 *
 * <ol>
 *   <li><b>Insert.</b> Batched inserts, table by table.</li>
 *   <li><b>Fixup.</b> Cycle-breaking foreign keys were seeded NULL because
 *       their parent did not exist yet. Now that every table is populated, they
 *       are filled by UPDATE.</li>
 *   <li><b>Sequence resync.</b> TestForge assigns primary keys explicitly, which
 *       leaves each identity sequence still sitting at 1. Without this pass the
 *       first row an application inserts into the seeded database collides with
 *       row 1 — the single most annoying way a test dataset can fail.</li>
 * </ol>
 */
@Component
public class Seeder {

    private static final Logger log = LoggerFactory.getLogger(Seeder.class);

    private final DatasetGenerator generator;
    private final TestForgeProperties properties;

    public Seeder(DatasetGenerator generator, TestForgeProperties properties) {
        this.generator = generator;
        this.properties = properties;
    }

    /**
     * Generates and seeds the whole dataset.
     *
     * @param connection an open connection to the ephemeral database
     * @param snapshot   the schema
     * @param plan       the generation plan
     * @param datasetId  salt scoping masked values to this dataset
     * @param progress   called after each table with (qualified table, rows written)
     */
    public SeedResult seed(Connection connection,
                           SchemaSnapshot snapshot,
                           GenerationPlan plan,
                           String datasetId,
                           BiConsumer<String, Long> progress) {
        Instant started = Instant.now();
        Map<String, Long> rowsPerTable = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        long totalRows = 0;
        long batches = 0;
        long fixupRows = 0;

        KeyPool pool = generator.newKeyPool(snapshot, plan);

        try {
            connection.setAutoCommit(false);

            for (TablePlan tablePlan : plan.tables()) {
                TableMeta table = snapshot.requireTable(tablePlan.table());
                List<Map<String, Object>> rows =
                        generator.generateTable(snapshot, plan, tablePlan, pool, datasetId);

                InsertOutcome outcome = insertRows(connection, table, tablePlan, rows);
                batches += outcome.batches();

                rowsPerTable.put(table.qualified(), outcome.rows());
                totalRows += outcome.rows();
                connection.commit();

                if (progress != null) {
                    progress.accept(table.qualified(), outcome.rows());
                }
            }

            fixupRows = runFixupPass(connection, snapshot, plan, pool, warnings);
            connection.commit();

            resyncSequences(connection, snapshot, plan, warnings);
            connection.commit();

        } catch (SQLException e) {
            rollbackQuietly(connection);
            throw new SeedingException("seeding failed after " + totalRows + " rows", e);
        }

        Duration elapsed = Duration.between(started, Instant.now());
        log.info("Seeded {} rows across {} tables in {} ms ({} rows/s)",
                totalRows, rowsPerTable.size(), elapsed.toMillis(),
                totalRows * 1_000 / Math.max(1, elapsed.toMillis()));

        return new SeedResult(rowsPerTable, totalRows, fixupRows, batches, elapsed, warnings);
    }

    // ---------------------------------------------------------------- insert

    private InsertOutcome insertRows(Connection connection, TableMeta table,
                                     TablePlan tablePlan, List<Map<String, Object>> rows) throws SQLException {
        List<ColumnPlan> columns = tablePlan.insertColumns();
        if (columns.isEmpty() || rows.isEmpty()) {
            return new InsertOutcome(0, 0);
        }

        Map<String, ColumnMeta> meta = table.columnsByName();
        String sql = buildInsert(table, columns, meta);
        int batchSize = properties.generation().batchSize();
        long inserted = 0;
        long batches = 0;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int pending = 0;
            for (Map<String, Object> row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    bind(connection, statement, i + 1, row.get(columns.get(i).column()),
                            meta.get(columns.get(i).column()));
                }
                statement.addBatch();
                pending++;

                if (pending >= batchSize) {
                    inserted += countUpdates(statement.executeBatch());
                    batches++;
                    pending = 0;
                }
            }
            if (pending > 0) {
                inserted += countUpdates(statement.executeBatch());
                batches++;
            }
        }
        return new InsertOutcome(inserted, batches);
    }

    /**
     * Builds the INSERT. {@code OVERRIDING SYSTEM VALUE} appears only when the
     * table has a GENERATED ALWAYS identity column, which PostgreSQL otherwise
     * refuses an explicit value for.
     */
    private String buildInsert(TableMeta table, List<ColumnPlan> columns, Map<String, ColumnMeta> meta) {
        boolean needsOverride = columns.stream()
                .map(column -> meta.get(column.column()))
                .anyMatch(column -> column != null && column.identityAlways());

        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table.ref().quoted()).append(" (");
        sql.append(columns.stream()
                .map(column -> Pg.quoteIdentifier(column.column()))
                .reduce((a, b) -> a + ", " + b)
                .orElse(""));
        sql.append(')');
        if (needsOverride) {
            sql.append(" OVERRIDING SYSTEM VALUE");
        }
        sql.append(" VALUES (");
        sql.append("?, ".repeat(columns.size() - 1)).append("?)");
        return sql.toString();
    }

    /**
     * Binds one value.
     *
     * <p>Most types the driver infers correctly from the Java object. The
     * exceptions are the PostgreSQL-specific types whose values arrive as
     * strings — jsonb, inet, interval and enums. Those are bound as
     * {@link Types#OTHER}, which tells the driver to hand the literal to the
     * server and let it apply the column's own input function, rather than
     * insisting the parameter is text and failing on the type mismatch.
     */
    private void bind(Connection connection, PreparedStatement statement,
                      int index, Object value, ColumnMeta column) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NULL);
            return;
        }
        if (column != null && column.isArray()) {
            String elementType = TypeMod.base(column.arrayElementType());
            statement.setArray(index, connection.createArrayOf(elementType, (Object[]) value));
            return;
        }
        if (value instanceof byte[] bytes) {
            statement.setBytes(index, bytes);
            return;
        }

        String type = column == null ? "" : TypeMod.base(column.udtName());
        boolean serverParsed = column != null && column.isEnum()
                || switch (type) {
            case "json", "jsonb", "inet", "cidr", "macaddr", "macaddr8", "interval", "money", "xml" -> true;
            default -> false;
        };

        if (serverParsed) {
            statement.setObject(index, String.valueOf(value), Types.OTHER);
        } else {
            statement.setObject(index, value);
        }
    }

    // ----------------------------------------------------------- fixup pass

    /**
     * Fills the foreign keys that were deferred to break a cycle.
     *
     * <p>Runs inside the same transaction with constraints set DEFERRED, so the
     * intermediate states while the update walks the table never trip the
     * constraint. The DDL writer created these foreign keys DEFERRABLE precisely
     * so this is possible without dropping and recreating them.
     */
    private long runFixupPass(Connection connection, SchemaSnapshot snapshot,
                              GenerationPlan plan, KeyPool pool, List<String> warnings) throws SQLException {
        List<TablePlan> needing = plan.tablesNeedingFixup();
        if (needing.isEmpty()) {
            return 0;
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("SET CONSTRAINTS ALL DEFERRED");
        }

        long updated = 0;
        for (TablePlan tablePlan : needing) {
            TableMeta table = snapshot.requireTable(tablePlan.table());
            var primaryKey = table.primaryKeyOpt().orElse(null);
            if (primaryKey == null) {
                warnings.add("Cannot fill the deferred foreign keys on " + table.qualified()
                        + ": the table has no primary key to address rows by, so those columns stay NULL.");
                continue;
            }

            for (ForeignKey fk : tablePlan.deferredEdges()) {
                updated += applyFixup(connection, table, tablePlan, fk, primaryKey.columns(), pool, plan.seed());
            }
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
        log.info("Filled {} deferred foreign key values across {} tables", updated, needing.size());
        return updated;
    }

    private long applyFixup(Connection connection, TableMeta table, TablePlan tablePlan,
                            ForeignKey fk, List<String> keyColumns, KeyPool pool, long seed) throws SQLException {
        List<Map<String, Object>> updates =
                generator.generateDeferredValues(table.ref(), fk, tablePlan.rowCount(), pool, seed);

        String setClause = fk.childColumns().stream()
                .map(column -> Pg.quoteIdentifier(column) + " = ?")
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow();
        String whereClause = keyColumns.stream()
                .map(column -> Pg.quoteIdentifier(column) + " = ?")
                .reduce((a, b) -> a + " AND " + b)
                .orElseThrow();
        String sql = "UPDATE " + table.ref().quoted() + " SET " + setClause + " WHERE " + whereClause;

        Map<String, ColumnMeta> meta = table.columnsByName();
        long updated = 0;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int rowIndex = 0; rowIndex < updates.size(); rowIndex++) {
                Map<String, Object> update = updates.get(rowIndex);
                if (update.values().stream().allMatch(java.util.Objects::isNull)) {
                    continue;
                }
                int index = 1;
                for (String column : fk.childColumns()) {
                    bind(connection, statement, index++, update.get(column), meta.get(column));
                }
                // Primary keys are the ordinals TestForge assigned during generation.
                for (String keyColumn : keyColumns) {
                    bind(connection, statement, index++, keyValueFor(table, keyColumn, rowIndex), meta.get(keyColumn));
                }
                statement.addBatch();
            }
            updated = countUpdates(statement.executeBatch());
        }
        return updated;
    }

    /** Mirrors the generator's primary key assignment: integer keys count from one. */
    private Object keyValueFor(TableMeta table, String column, int rowIndex) {
        ColumnMeta meta = table.requireColumn(column);
        return switch (TypeMod.base(meta.udtName())) {
            case "int2" -> (short) (rowIndex + 1);
            case "int4" -> rowIndex + 1;
            case "int8" -> (long) rowIndex + 1;
            default -> null;
        };
    }

    // ------------------------------------------------------ sequence resync

    /**
     * Advances every identity sequence past the keys already seeded.
     *
     * <p>Without this the sequence still returns 1, and the first insert an
     * application makes against the seeded database collides with the first
     * seeded row. {@code pg_get_serial_sequence} resolves both serial-backed and
     * identity columns, so one query handles either spelling.
     */
    private void resyncSequences(Connection connection, SchemaSnapshot snapshot,
                                 GenerationPlan plan, List<String> warnings) {
        int resynced = 0;
        for (TablePlan tablePlan : plan.tables()) {
            TableMeta table = snapshot.requireTable(tablePlan.table());
            for (ColumnMeta column : table.columns()) {
                if (!column.identity() && !column.serial()) {
                    continue;
                }
                String sql = "SELECT setval(pg_get_serial_sequence(?, ?), "
                        + "COALESCE((SELECT MAX(" + Pg.quoteIdentifier(column.name()) + ") FROM "
                        + table.ref().quoted() + "), 0) + 1, false)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, table.qualified());
                    statement.setString(2, column.name());
                    statement.execute();
                    resynced++;
                } catch (SQLException e) {
                    warnings.add("Could not advance the sequence behind " + table.qualified() + "."
                            + column.name() + "; the first application insert may collide. "
                            + e.getMessage());
                }
            }
        }
        log.debug("Resynced {} identity sequences", resynced);
    }

    // -------------------------------------------------------------- helpers

    private static long countUpdates(int[] results) {
        long total = 0;
        for (int result : results) {
            // SUCCESS_NO_INFO is returned by batched statements that succeeded
            // without reporting a row count; it still means one row was written.
            total += result >= 0 ? result : (result == Statement.SUCCESS_NO_INFO ? 1 : 0);
        }
        return total;
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("Rollback after a seeding failure did not succeed: {}", e.getMessage());
        }
    }

    private record InsertOutcome(long rows, long batches) {
    }

    /** Raised when the dataset cannot be written. */
    public static class SeedingException extends RuntimeException {

        public SeedingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
