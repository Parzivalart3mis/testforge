package com.helios.testforge.domain.schema;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The complete introspected picture of one schema at one moment.
 *
 * <p>Snapshots are immutable and content-addressed by {@code fingerprint}, so a
 * dataset request can record exactly which shape of the schema it was planned
 * against. If the target schema drifts, the next introspection produces a
 * different fingerprint and the console can flag stale plans rather than
 * silently seeding against a schema that no longer exists.
 *
 * @param database    the database the snapshot was taken from
 * @param schema      the schema name
 * @param capturedAt  when introspection ran
 * @param tables      every base table in the schema, in dependency-agnostic catalog order
 * @param fingerprint stable hash over the structural content of {@code tables}
 */
public record SchemaSnapshot(
        String database,
        String schema,
        Instant capturedAt,
        List<TableMeta> tables,
        String fingerprint) {

    public SchemaSnapshot {
        tables = List.copyOf(tables);
    }

    public int tableCount() {
        return tables.size();
    }

    public long columnCount() {
        return tables.stream().mapToLong(t -> t.columns().size()).sum();
    }

    public long foreignKeyCount() {
        return tables.stream().mapToLong(t -> t.foreignKeys().size()).sum();
    }

    public Map<TableRef, TableMeta> byRef() {
        Map<TableRef, TableMeta> index = new LinkedHashMap<>();
        for (TableMeta table : tables) {
            index.put(table.ref(), table);
        }
        return index;
    }

    public Optional<TableMeta> table(TableRef ref) {
        return tables.stream().filter(t -> t.ref().equals(ref)).findFirst();
    }

    public TableMeta requireTable(TableRef ref) {
        return table(ref).orElseThrow(() -> new IllegalArgumentException(
                "schema " + schema + " has no table " + ref.qualified()));
    }

    /** Every foreign key in the schema, flattened across tables. */
    public List<ForeignKey> allForeignKeys() {
        return tables.stream().flatMap(t -> t.foreignKeys().stream()).toList();
    }

    /** Columns classified as sensitive, which the masking policy will act on by default. */
    public List<String> sensitiveColumns() {
        return tables.stream()
                .flatMap(t -> t.columns().stream()
                        .filter(c -> c.dataClass().sensitive())
                        .map(c -> t.qualified() + "." + c.name()))
                .toList();
    }

    /** A snapshot restricted to the given tables, used when a request targets a subset. */
    public SchemaSnapshot restrictedTo(List<TableRef> keep) {
        List<TableMeta> subset = tables.stream().filter(t -> keep.contains(t.ref())).toList();
        return new SchemaSnapshot(database, schema, capturedAt, subset, fingerprint);
    }
}
