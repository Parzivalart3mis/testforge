package com.helios.testforge.domain.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One table and everything the platform knows about it. Columns are held in
 * catalog order so generated DDL and INSERT statements match the shape a
 * developer would expect to see.
 *
 * @param ref            schema-qualified identity
 * @param columns        columns in ordinal order
 * @param primaryKey     the primary key, absent for the (rare, but legal) table without one
 * @param foreignKeys    outbound foreign keys — edges to the tables this one depends on
 * @param uniques        unique constraints and unique indexes
 * @param checks         check constraints
 * @param estimatedRows  planner row estimate from {@code pg_class.reltuples}, {@code -1} when never analysed
 * @param comment        table comment, when one is set
 */
public record TableMeta(
        TableRef ref,
        List<ColumnMeta> columns,
        PrimaryKey primaryKey,
        List<ForeignKey> foreignKeys,
        List<UniqueConstraint> uniques,
        List<CheckConstraint> checks,
        long estimatedRows,
        String comment) {

    public TableMeta {
        columns = List.copyOf(columns);
        foreignKeys = foreignKeys == null ? List.of() : List.copyOf(foreignKeys);
        uniques = uniques == null ? List.of() : List.copyOf(uniques);
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public String name() {
        return ref.name();
    }

    public String schema() {
        return ref.schema();
    }

    public String qualified() {
        return ref.qualified();
    }

    public Optional<PrimaryKey> primaryKeyOpt() {
        return Optional.ofNullable(primaryKey);
    }

    public Optional<ColumnMeta> column(String name) {
        return columns.stream().filter(c -> c.name().equals(name)).findFirst();
    }

    public ColumnMeta requireColumn(String name) {
        return column(name).orElseThrow(() -> new IllegalArgumentException(
                "table " + qualified() + " has no column " + name));
    }

    /** Columns indexed by name, preserving ordinal order. */
    public Map<String, ColumnMeta> columnsByName() {
        Map<String, ColumnMeta> byName = new LinkedHashMap<>();
        for (ColumnMeta column : columns) {
            byName.put(column.name(), column);
        }
        return byName;
    }

    /** Outbound foreign keys excluding self-references, i.e. the true graph edges. */
    public List<ForeignKey> dependencyEdges() {
        return foreignKeys.stream().filter(fk -> !fk.isSelfReference()).toList();
    }

    public List<ForeignKey> selfReferences() {
        return foreignKeys.stream().filter(ForeignKey::isSelfReference).toList();
    }

    /** The foreign key that owns the given column, if any. */
    public Optional<ForeignKey> foreignKeyFor(String column) {
        return foreignKeys.stream().filter(fk -> fk.childColumns().contains(column)).findFirst();
    }

    /** Whether the column participates in any uniqueness guarantee, PK included. */
    public boolean isUniqueColumn(String column) {
        if (primaryKey != null && primaryKey.columns().equals(List.of(column))) {
            return true;
        }
        return uniques.stream().anyMatch(u -> u.columns().equals(List.of(column)));
    }
}
