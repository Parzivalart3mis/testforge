package com.helios.testforge.introspect;

import com.helios.testforge.domain.schema.CheckConstraint;
import com.helios.testforge.domain.schema.ColumnMeta;
import com.helios.testforge.domain.schema.DataClass;
import com.helios.testforge.domain.schema.ForeignKey;
import com.helios.testforge.domain.schema.PrimaryKey;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.domain.schema.TableMeta;
import com.helios.testforge.domain.schema.TableRef;
import com.helios.testforge.domain.schema.UniqueConstraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads a PostgreSQL schema out of {@code pg_catalog}.
 *
 * <p>Five catalog queries — tables, columns, enum labels, constraints, unique
 * indexes — are issued per schema rather than one query per table, so
 * introspecting a 200-table schema costs five round trips instead of six
 * hundred. The result is assembled in memory and hashed into a fingerprint that
 * makes an unchanged schema reuse its previous snapshot row.
 *
 * <p>Everything here is read-only. A target may safely point at a production
 * replica: TestForge learns the shape of the data and never reads a row of it.
 */
@Component
public class PostgresSchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(PostgresSchemaIntrospector.class);

    /** Base tables and partitioned tables. Views, foreign tables and sequences are not seedable. */
    private static final String TABLE_SQL = """
            SELECT c.relname                                AS table_name,
                   GREATEST(c.reltuples, -1)::bigint        AS estimated_rows,
                   obj_description(c.oid, 'pg_class')       AS table_comment
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND c.relkind IN ('r', 'p')
              AND NOT c.relispartition
            ORDER BY c.relname
            """;

    private static final String COLUMN_SQL = """
            SELECT c.relname                                       AS table_name,
                   a.attname                                       AS column_name,
                   a.attnum                                        AS ordinal,
                   pg_catalog.format_type(a.atttypid, a.atttypmod) AS formatted_type,
                   t.typname                                       AS udt_name,
                   t.typtype                                       AS type_kind,
                   a.atttypmod                                     AS type_mod,
                   a.attnotnull                                    AS not_null,
                   pg_catalog.pg_get_expr(d.adbin, d.adrelid)      AS default_expr,
                   a.attidentity                                   AS identity_kind,
                   a.attgenerated                                  AS generated_kind,
                   et.typname                                      AS element_type,
                   col_description(c.oid, a.attnum)                AS column_comment
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n  ON n.oid = c.relnamespace
            JOIN pg_catalog.pg_attribute a  ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
            JOIN pg_catalog.pg_type t       ON t.oid = a.atttypid
            LEFT JOIN pg_catalog.pg_type et ON et.oid = t.typelem AND t.typcategory = 'A'
            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
            WHERE n.nspname = ?
              AND c.relkind IN ('r', 'p')
              AND NOT c.relispartition
            ORDER BY c.relname, a.attnum
            """;

    /**
     * Enum labels are looked up across every namespace, not just the target
     * schema: a schema very often uses enum types that live in a shared one.
     */
    private static final String ENUM_SQL = """
            SELECT t.typname AS type_name, e.enumlabel AS label
            FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_enum e ON e.enumtypid = t.oid
            ORDER BY t.typname, e.enumsortorder
            """;

    private static final String CONSTRAINT_SQL = """
            SELECT con.conname                                AS constraint_name,
                   con.contype                                AS constraint_type,
                   con.condeferrable                          AS deferrable,
                   c.relname                                  AS table_name,
                   rn.nspname                                 AS ref_schema,
                   rc.relname                                 AS ref_table,
                   con.confupdtype                            AS on_update,
                   con.confdeltype                            AS on_delete,
                   pg_catalog.pg_get_constraintdef(con.oid)   AS definition,
                   (SELECT array_agg(att.attname ORDER BY k.ord)
                      FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                      JOIN pg_catalog.pg_attribute att
                        ON att.attrelid = con.conrelid AND att.attnum = k.attnum) AS child_columns,
                   (SELECT array_agg(att.attname ORDER BY k.ord)
                      FROM unnest(con.confkey) WITH ORDINALITY AS k(attnum, ord)
                      JOIN pg_catalog.pg_attribute att
                        ON att.attrelid = con.confrelid AND att.attnum = k.attnum) AS parent_columns
            FROM pg_catalog.pg_constraint con
            JOIN pg_catalog.pg_class c      ON c.oid = con.conrelid
            JOIN pg_catalog.pg_namespace n  ON n.oid = c.relnamespace
            LEFT JOIN pg_catalog.pg_class rc     ON rc.oid = con.confrelid
            LEFT JOIN pg_catalog.pg_namespace rn ON rn.oid = rc.relnamespace
            WHERE n.nspname = ?
              AND con.contype IN ('p', 'f', 'u', 'c')
              AND c.relkind IN ('r', 'p')
            ORDER BY c.relname, con.contype, con.conname
            """;

    /**
     * Unique indexes with no backing constraint. Partial indexes are skipped:
     * their uniqueness only holds over a predicate the generator cannot honour,
     * so treating them as unconditional would over-constrain generation.
     */
    private static final String UNIQUE_INDEX_SQL = """
            SELECT c.relname AS table_name,
                   i.relname AS index_name,
                   array_agg(a.attname ORDER BY k.ord) AS columns
            FROM pg_catalog.pg_index x
            JOIN pg_catalog.pg_class c     ON c.oid = x.indrelid
            JOIN pg_catalog.pg_class i     ON i.oid = x.indexrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            JOIN unnest(x.indkey) WITH ORDINALITY AS k(attnum, ord) ON k.attnum > 0
            JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
            WHERE n.nspname = ?
              AND x.indisunique
              AND NOT x.indisprimary
              AND x.indpred IS NULL
              AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint con WHERE con.conindid = i.oid)
            GROUP BY c.relname, i.relname
            ORDER BY c.relname, i.relname
            """;

    /**
     * Introspects one schema.
     *
     * @param dataSource a connection to the target database
     * @param schema     the schema to read
     * @return an immutable snapshot, fingerprinted by structural content
     */
    public SchemaSnapshot introspect(DataSource dataSource, String schema) {
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            String database = connection.getCatalog();

            Map<String, TableShell> shells = readTables(connection, schema);
            if (shells.isEmpty()) {
                throw new SchemaIntrospectionException(
                        "schema '" + schema + "' contains no base tables, or the connecting role cannot see them");
            }

            Map<String, List<String>> enumLabels = readEnumLabels(connection);
            Constraints constraints = readConstraints(connection, schema);
            readUniqueIndexes(connection, schema, constraints);
            Map<String, List<RawColumn>> columns = readColumns(connection, schema);

            List<TableMeta> tables = assemble(schema, shells, columns, enumLabels, constraints);
            SchemaSnapshot snapshot = new SchemaSnapshot(
                    database, schema, Instant.now(), tables, fingerprint(tables));

            log.info("Introspected {}.{}: {} tables, {} columns, {} foreign keys in {} ms",
                    database, schema, snapshot.tableCount(), snapshot.columnCount(),
                    snapshot.foreignKeyCount(), (System.nanoTime() - start) / 1_000_000);
            return snapshot;
        } catch (SQLException e) {
            throw new SchemaIntrospectionException("failed to introspect schema '" + schema + "'", e);
        }
    }

    // ---------------------------------------------------------------- reads

    private Map<String, TableShell> readTables(Connection connection, String schema) throws SQLException {
        Map<String, TableShell> shells = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(TABLE_SQL)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("table_name");
                    shells.put(name, new TableShell(name, rs.getLong("estimated_rows"), rs.getString("table_comment")));
                }
            }
        }
        return shells;
    }

    private Map<String, List<RawColumn>> readColumns(Connection connection, String schema) throws SQLException {
        Map<String, List<RawColumn>> byTable = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(COLUMN_SQL)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RawColumn column = new RawColumn(
                            rs.getString("column_name"),
                            rs.getInt("ordinal"),
                            rs.getString("formatted_type"),
                            rs.getString("udt_name"),
                            rs.getString("type_kind"),
                            rs.getInt("type_mod"),
                            rs.getBoolean("not_null"),
                            rs.getString("default_expr"),
                            rs.getString("identity_kind"),
                            rs.getString("generated_kind"),
                            rs.getString("element_type"),
                            rs.getString("column_comment"));
                    byTable.computeIfAbsent(rs.getString("table_name"), k -> new ArrayList<>()).add(column);
                }
            }
        }
        return byTable;
    }

    private Map<String, List<String>> readEnumLabels(Connection connection) throws SQLException {
        Map<String, List<String>> labels = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(ENUM_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                labels.computeIfAbsent(rs.getString("type_name"), k -> new ArrayList<>())
                        .add(rs.getString("label"));
            }
        }
        return labels;
    }

    private Constraints readConstraints(Connection connection, String schema) throws SQLException {
        Constraints constraints = new Constraints();
        try (PreparedStatement ps = connection.prepareStatement(CONSTRAINT_SQL)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("table_name");
                    String name = rs.getString("constraint_name");
                    String type = rs.getString("constraint_type");
                    List<String> childColumns = toList(rs.getArray("child_columns"));

                    switch (type) {
                        case "p" -> constraints.primaryKeys.put(table, new PrimaryKey(name, childColumns));
                        case "u" -> constraints.uniques
                                .computeIfAbsent(table, k -> new ArrayList<>())
                                .add(new UniqueConstraint(name, childColumns, false));
                        case "c" -> constraints.checks
                                .computeIfAbsent(table, k -> new ArrayList<>())
                                .add(new CheckConstraint(name, rs.getString("definition"), childColumns));
                        case "f" -> {
                            String refSchema = rs.getString("ref_schema");
                            String refTable = rs.getString("ref_table");
                            if (refTable == null) {
                                log.warn("Skipping foreign key {} on {}: referenced relation is not visible",
                                        name, table);
                                break;
                            }
                            constraints.foreignKeys
                                    .computeIfAbsent(table, k -> new ArrayList<>())
                                    .add(new ForeignKey(
                                            name,
                                            TableRef.of(schema, table),
                                            childColumns,
                                            TableRef.of(refSchema, refTable),
                                            toList(rs.getArray("parent_columns")),
                                            referentialAction(rs.getString("on_delete")),
                                            referentialAction(rs.getString("on_update")),
                                            rs.getBoolean("deferrable")));
                        }
                        default -> log.debug("Ignoring constraint {} of type {}", name, type);
                    }
                }
            }
        }
        return constraints;
    }

    private void readUniqueIndexes(Connection connection, String schema, Constraints constraints) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(UNIQUE_INDEX_SQL)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    constraints.uniques
                            .computeIfAbsent(rs.getString("table_name"), k -> new ArrayList<>())
                            .add(new UniqueConstraint(rs.getString("index_name"), toList(rs.getArray("columns")), true));
                }
            }
        }
    }

    // ------------------------------------------------------------- assembly

    private List<TableMeta> assemble(String schema,
                                     Map<String, TableShell> shells,
                                     Map<String, List<RawColumn>> columnsByTable,
                                     Map<String, List<String>> enumLabels,
                                     Constraints constraints) {
        List<TableMeta> tables = new ArrayList<>(shells.size());

        for (TableShell shell : shells.values()) {
            TableRef ref = TableRef.of(schema, shell.name());
            PrimaryKey primaryKey = constraints.primaryKeys.get(shell.name());
            List<ForeignKey> foreignKeys = constraints.foreignKeys.getOrDefault(shell.name(), List.of());
            List<UniqueConstraint> uniques = constraints.uniques.getOrDefault(shell.name(), List.of());
            List<CheckConstraint> checks = constraints.checks.getOrDefault(shell.name(), List.of());

            Set<String> pkColumns = primaryKey == null ? Set.of() : Set.copyOf(primaryKey.columns());
            Set<String> fkColumns = new HashSet<>();
            foreignKeys.forEach(fk -> fkColumns.addAll(fk.childColumns()));

            List<RawColumn> raw = columnsByTable.getOrDefault(shell.name(), List.of());
            List<ColumnMeta> columns = new ArrayList<>(raw.size());
            for (RawColumn rc : raw) {
                columns.add(toColumnMeta(rc, enumLabels, pkColumns.contains(rc.name()), fkColumns.contains(rc.name())));
            }

            tables.add(new TableMeta(ref, columns, primaryKey,
                    sortedByName(foreignKeys, ForeignKey::name),
                    sortedByName(uniques, UniqueConstraint::name),
                    sortedByName(checks, CheckConstraint::name),
                    shell.estimatedRows(), shell.comment()));
        }
        return tables;
    }

    private ColumnMeta toColumnMeta(RawColumn rc, Map<String, List<String>> enumLabels,
                                    boolean isPrimaryKey, boolean isForeignKey) {
        boolean isEnum = "e".equals(rc.typeKind());
        List<String> labels = isEnum ? enumLabels.getOrDefault(rc.udtName(), List.of()) : List.of();

        boolean identity = rc.identityKind() != null && !rc.identityKind().isBlank();
        String identityGeneration = switch (rc.identityKind() == null ? "" : rc.identityKind()) {
            case "a" -> "ALWAYS";
            case "d" -> "BY DEFAULT";
            default -> null;
        };
        boolean generated = "s".equals(rc.generatedKind());
        boolean serial = rc.defaultExpr() != null && rc.defaultExpr().startsWith("nextval(");

        DataClass dataClass = DataClassifier.classify(
                rc.name(), rc.udtName(), isEnum, isPrimaryKey, isForeignKey);

        return new ColumnMeta(
                rc.name(),
                rc.ordinal(),
                rc.formattedType(),
                rc.udtName(),
                rc.formattedType(),
                !rc.notNull(),
                TypeMod.characterMaxLength(rc.udtName(), rc.typeMod()),
                TypeMod.numericPrecision(rc.udtName(), rc.typeMod()),
                TypeMod.numericScale(rc.udtName(), rc.typeMod()),
                rc.defaultExpr(),
                identity,
                identityGeneration,
                generated,
                generated ? rc.defaultExpr() : null,
                serial,
                labels,
                rc.elementType(),
                rc.comment(),
                dataClass);
    }

    // -------------------------------------------------------------- helpers

    /**
     * Hashes the structural content of a schema. Two introspections of an
     * unchanged schema produce the same fingerprint, so the control plane can
     * reuse a snapshot row; any DDL change produces a different one, so a plan
     * built against the old shape can be flagged as stale.
     */
    static String fingerprint(List<TableMeta> tables) {
        StringBuilder canonical = new StringBuilder();
        tables.stream().sorted(Comparator.comparing(TableMeta::ref)).forEach(table -> {
            canonical.append("T:").append(table.qualified()).append('\n');
            table.columns().forEach(column -> canonical
                    .append("  C:").append(column.name())
                    .append('|').append(column.formattedType())
                    .append('|').append(column.nullable())
                    .append('|').append(column.defaultExpression())
                    .append('|').append(column.identity())
                    .append('|').append(column.generated())
                    .append('|').append(String.join(",", column.enumLabels()))
                    .append('\n'));
            table.primaryKeyOpt().ifPresent(pk -> canonical
                    .append("  P:").append(String.join(",", pk.columns())).append('\n'));
            table.foreignKeys().forEach(fk -> canonical
                    .append("  F:").append(fk.name()).append('|').append(fk.describe()).append('\n'));
            table.uniques().forEach(u -> canonical
                    .append("  U:").append(u.name()).append('|').append(String.join(",", u.columns())).append('\n'));
            table.checks().forEach(c -> canonical
                    .append("  K:").append(c.name()).append('|').append(c.expression()).append('\n'));
        });

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    /** Maps PostgreSQL's single-character referential action codes to SQL keywords. */
    private static String referentialAction(String code) {
        if (code == null || code.isBlank()) {
            return "NO ACTION";
        }
        return switch (code.charAt(0)) {
            case 'a' -> "NO ACTION";
            case 'r' -> "RESTRICT";
            case 'c' -> "CASCADE";
            case 'n' -> "SET NULL";
            case 'd' -> "SET DEFAULT";
            default -> "NO ACTION";
        };
    }

    private static List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        try {
            Object raw = array.getArray();
            if (raw instanceof Object[] values) {
                List<String> result = new ArrayList<>(values.length);
                for (Object value : values) {
                    result.add(value == null ? null : value.toString());
                }
                return result;
            }
            return List.of();
        } finally {
            array.free();
        }
    }

    /** Sorts by name so introspection output — and therefore the fingerprint — is stable. */
    private static <T> List<T> sortedByName(List<T> items, java.util.function.Function<T, String> nameOf) {
        return items.stream().sorted(Comparator.comparing(nameOf)).toList();
    }

    // ----------------------------------------------------------- row shapes

    private record TableShell(String name, long estimatedRows, String comment) {
    }

    private record RawColumn(
            String name, int ordinal, String formattedType, String udtName, String typeKind,
            int typeMod, boolean notNull, String defaultExpr, String identityKind,
            String generatedKind, String elementType, String comment) {
    }

    private static final class Constraints {
        final Map<String, PrimaryKey> primaryKeys = new HashMap<>();
        final Map<String, List<ForeignKey>> foreignKeys = new TreeMap<>();
        final Map<String, List<UniqueConstraint>> uniques = new TreeMap<>();
        final Map<String, List<CheckConstraint>> checks = new TreeMap<>();
    }
}
