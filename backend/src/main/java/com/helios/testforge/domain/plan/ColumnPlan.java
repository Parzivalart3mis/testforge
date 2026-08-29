package com.helios.testforge.domain.plan;

import com.helios.testforge.domain.request.MaskStrategy;
import com.helios.testforge.domain.schema.DataClass;

/**
 * The decision made for one column, in a form the console can render and a
 * reviewer can audit before a single row is written.
 *
 * @param column       column name
 * @param sqlType      the column's rendered SQL type
 * @param dataClass    inferred semantic class
 * @param role         what supplies the value
 * @param generator    human-readable name of the chosen generator
 * @param mask         masking strategy applied after generation
 * @param maskSource   why that strategy was chosen, e.g. an explicit rule or the sensitivity default
 * @param unique       whether the value must be unique within the table
 * @param nullable     whether the column accepts NULL
 * @param parentTable  referenced table, when the role is a foreign key
 * @param parentColumn referenced column, when the role is a foreign key
 */
public record ColumnPlan(
        String column,
        String sqlType,
        DataClass dataClass,
        ColumnRole role,
        String generator,
        MaskStrategy mask,
        String maskSource,
        boolean unique,
        boolean nullable,
        String parentTable,
        String parentColumn) {

    public boolean isMasked() {
        return mask != MaskStrategy.PRESERVE;
    }

    public boolean included() {
        return role.included();
    }
}
