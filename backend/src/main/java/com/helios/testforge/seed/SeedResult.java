package com.helios.testforge.seed;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What seeding actually wrote.
 *
 * @param rowsPerTable   rows inserted per qualified table name, in seed order
 * @param totalRows      total rows inserted
 * @param fixupRows      rows updated by the cycle-breaking second pass
 * @param batches        JDBC batches executed
 * @param elapsed        wall-clock duration
 * @param warnings       anything that was relaxed or skipped
 */
public record SeedResult(
        Map<String, Long> rowsPerTable,
        long totalRows,
        long fixupRows,
        long batches,
        Duration elapsed,
        List<String> warnings) {

    public SeedResult {
        rowsPerTable = new LinkedHashMap<>(rowsPerTable);
        warnings = List.copyOf(warnings);
    }

    public int tableCount() {
        return rowsPerTable.size();
    }

    /** Rows per second, the number the console reports for a completed run. */
    public long rowsPerSecond() {
        long millis = Math.max(1, elapsed.toMillis());
        return totalRows * 1_000 / millis;
    }
}
