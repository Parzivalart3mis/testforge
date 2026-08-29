package com.helios.testforge.domain.schema;

import com.helios.testforge.util.Pg;

/**
 * A schema-qualified table name. Used as the node identity in the foreign-key
 * graph, so equality and hashing must be exact — PostgreSQL folds unquoted
 * identifiers to lower case, and everything reaching this record has already
 * come back from {@code information_schema} in folded form.
 */
public record TableRef(String schema, String name) implements Comparable<TableRef> {

    public TableRef {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("schema must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("table name must not be blank");
        }
    }

    public static TableRef of(String schema, String name) {
        return new TableRef(schema, name);
    }

    /** Parses {@code schema.table}; a bare name is resolved against {@code defaultSchema}. */
    public static TableRef parse(String qualified, String defaultSchema) {
        int dot = qualified.indexOf('.');
        return dot < 0
                ? new TableRef(defaultSchema, qualified)
                : new TableRef(qualified.substring(0, dot), qualified.substring(dot + 1));
    }

    /** {@code schema.table} — the canonical form used in APIs, logs and plan output. */
    public String qualified() {
        return schema + "." + name;
    }

    /** {@code "schema"."table"} — safe for interpolation into SQL. */
    public String quoted() {
        return Pg.quoteIdentifier(schema) + "." + Pg.quoteIdentifier(name);
    }

    @Override
    public int compareTo(TableRef other) {
        int bySchema = schema.compareTo(other.schema);
        return bySchema != 0 ? bySchema : name.compareTo(other.name);
    }

    @Override
    public String toString() {
        return qualified();
    }
}
