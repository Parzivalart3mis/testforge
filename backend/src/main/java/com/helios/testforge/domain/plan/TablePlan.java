package com.helios.testforge.domain.plan;

import com.helios.testforge.domain.schema.ForeignKey;
import com.helios.testforge.domain.schema.TableRef;

import java.util.List;

/**
 * How one table will be seeded.
 *
 * @param table          the table
 * @param order          its position in the seed order, zero-based
 * @param depth          its dependency depth, for display
 * @param rowCount       how many rows to generate
 * @param columns        the per-column decisions, in catalog order
 * @param deferredEdges  cycle-breaking foreign keys filled by the second pass
 * @param selfEdges      self-references satisfied from earlier rows of this table
 */
public record TablePlan(
        TableRef table,
        int order,
        int depth,
        int rowCount,
        List<ColumnPlan> columns,
        List<ForeignKey> deferredEdges,
        List<ForeignKey> selfEdges) {

    public TablePlan {
        columns = List.copyOf(columns);
        deferredEdges = List.copyOf(deferredEdges);
        selfEdges = List.copyOf(selfEdges);
    }

    /** Columns that appear in the INSERT statement, in order. */
    public List<ColumnPlan> insertColumns() {
        return columns.stream().filter(ColumnPlan::included).toList();
    }

    public List<ColumnPlan> maskedColumns() {
        return columns.stream().filter(ColumnPlan::isMasked).toList();
    }

    public boolean hasDeferredEdges() {
        return !deferredEdges.isEmpty();
    }
}
