package com.helios.testforge.domain.schema;

import java.util.List;

/**
 * A CHECK constraint. TestForge does not attempt to solve arbitrary check
 * expressions; it recognises the two shapes that appear constantly in real
 * schemas — {@code col IN ('a','b')} and simple numeric range bounds — and
 * feeds those back into generation so seeding does not trip the constraint.
 * Anything it cannot interpret is still recorded, and surfaces in the plan as a
 * warning so the requester knows a manual override may be needed.
 */
public record CheckConstraint(String name, String expression, List<String> columns) {

    public CheckConstraint {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
