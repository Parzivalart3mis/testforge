package com.helios.testforge.domain.schema;

import java.util.List;

/**
 * A table's primary key. Composite keys keep their declared column order, which
 * the generator relies on when it builds the parent-key pool that child foreign
 * keys draw from.
 */
public record PrimaryKey(String name, List<String> columns) {

    public PrimaryKey {
        columns = List.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("primary key " + name + " has no columns");
        }
    }

    public boolean isComposite() {
        return columns.size() > 1;
    }

    public boolean covers(String column) {
        return columns.contains(column);
    }
}
