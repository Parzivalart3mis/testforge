package com.helios.testforge.graph;

import com.helios.testforge.domain.schema.TableRef;

import java.util.List;

/**
 * Raised when a foreign-key cycle cannot be broken.
 *
 * <p>A cycle is only fatal when every edge in it is NOT NULL: there is then no
 * order in which the first row of any table in the cycle can be inserted, and
 * no deferred second pass can help either, because the row cannot exist in the
 * first place. The message names the cycle so the requester can see exactly
 * which constraints are mutually blocking.
 */
public class CyclicSchemaException extends RuntimeException {

    private final transient List<TableRef> cycle;

    public CyclicSchemaException(List<TableRef> cycle, String detail) {
        super("foreign-key cycle cannot be broken: "
                + cycle.stream().map(TableRef::qualified).reduce((a, b) -> a + " -> " + b).orElse("?")
                + " -> " + (cycle.isEmpty() ? "?" : cycle.getFirst().qualified())
                + ". " + detail);
        this.cycle = List.copyOf(cycle);
    }

    /** The tables forming the cycle, in traversal order. */
    public List<TableRef> cycle() {
        return cycle;
    }
}
