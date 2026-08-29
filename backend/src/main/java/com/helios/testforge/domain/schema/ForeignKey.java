package com.helios.testforge.domain.schema;

import java.util.List;

/**
 * A foreign-key constraint, directed child → parent. This is the edge type of
 * the dependency graph: the parent must be seeded first so the child has real
 * keys to point at.
 *
 * @param name          constraint name
 * @param child         the referencing table
 * @param childColumns  referencing columns, in constraint order
 * @param parent        the referenced table
 * @param parentColumns referenced columns, positionally paired with {@code childColumns}
 * @param onDelete      referential action, e.g. {@code NO ACTION}, {@code CASCADE}
 * @param onUpdate      referential action for updates
 * @param deferrable    whether the constraint is declared DEFERRABLE
 */
public record ForeignKey(
        String name,
        TableRef child,
        List<String> childColumns,
        TableRef parent,
        List<String> parentColumns,
        String onDelete,
        String onUpdate,
        boolean deferrable) {

    public ForeignKey {
        childColumns = List.copyOf(childColumns);
        parentColumns = List.copyOf(parentColumns);
        if (childColumns.size() != parentColumns.size()) {
            throw new IllegalArgumentException(
                    "foreign key " + name + " pairs " + childColumns.size()
                            + " child columns with " + parentColumns.size() + " parent columns");
        }
        if (childColumns.isEmpty()) {
            throw new IllegalArgumentException("foreign key " + name + " has no columns");
        }
    }

    /**
     * A foreign key whose child and parent are the same table. These never need
     * to break a cycle: rows are generated in index order, so a self-reference
     * can simply point at an already-generated row of the same table.
     */
    public boolean isSelfReference() {
        return child.equals(parent);
    }

    public boolean isComposite() {
        return childColumns.size() > 1;
    }

    public String describe() {
        return child.qualified() + "(" + String.join(", ", childColumns) + ") -> "
                + parent.qualified() + "(" + String.join(", ", parentColumns) + ")";
    }
}
