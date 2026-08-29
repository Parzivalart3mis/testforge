package com.helios.testforge.domain.plan;

import com.helios.testforge.domain.schema.TableRef;

import java.util.List;
import java.util.Optional;

/**
 * The complete, reviewable description of what seeding will do.
 *
 * <p>A plan is produced and returned before any database is touched, which is
 * the point: a requester can see the table order, the row counts, and — most
 * importantly — exactly which columns will be masked and why, before a single
 * row exists. It is also the reproducibility record. Re-running the same plan
 * with the same seed against the same schema fingerprint produces an identical
 * dataset.
 *
 * @param seed                the root seed every value derives from
 * @param schema              the schema being modelled
 * @param snapshotFingerprint the schema shape this plan was built against
 * @param tables              table plans, already in seed order
 * @param totalRows           sum of all row counts
 * @param maskedColumns       how many columns will be masked
 * @param warnings            relaxations and caveats the requester should see
 */
public record GenerationPlan(
        long seed,
        String schema,
        String snapshotFingerprint,
        List<TablePlan> tables,
        long totalRows,
        int maskedColumns,
        List<String> warnings) {

    public GenerationPlan {
        tables = List.copyOf(tables);
        warnings = List.copyOf(warnings);
    }

    public int tableCount() {
        return tables.size();
    }

    public Optional<TablePlan> forTable(TableRef ref) {
        return tables.stream().filter(t -> t.table().equals(ref)).findFirst();
    }

    /** Tables that need the second, cycle-filling UPDATE pass. */
    public List<TablePlan> tablesNeedingFixup() {
        return tables.stream().filter(TablePlan::hasDeferredEdges).toList();
    }

    /** Every masked column, as {@code table.column} paired with its strategy, for the audit record. */
    public List<String> maskingSummary() {
        return tables.stream()
                .flatMap(table -> table.maskedColumns().stream()
                        .map(column -> table.table().qualified() + "." + column.column()
                                + " -> " + column.mask() + " (" + column.maskSource() + ")"))
                .toList();
    }
}
