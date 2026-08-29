package com.helios.testforge.domain.schema;

import java.util.List;

/**
 * A uniqueness guarantee, from either a UNIQUE constraint or a unique index.
 * The generator consults these to know which columns need collision tracking.
 */
public record UniqueConstraint(String name, List<String> columns, boolean fromIndex) {

    public UniqueConstraint {
        columns = List.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("unique constraint " + name + " has no columns");
        }
    }

    public boolean isComposite() {
        return columns.size() > 1;
    }
}
