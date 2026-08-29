package com.helios.testforge.domain.request;

import java.util.Map;

/**
 * A masking override for the columns matched by {@code tablePattern} and
 * {@code columnPattern}. Patterns are globs ({@code *} matches any run of
 * characters), so a rule can target one column, a table, or every column with a
 * given name across the whole schema — {@code *.email}, for instance.
 *
 * <p>Rules are evaluated most-specific-first; see {@code MaskingPolicy}.
 *
 * @param tablePattern  glob matched against the schema-qualified table name
 * @param columnPattern glob matched against the column name
 * @param strategy      the transformation to apply
 * @param options       strategy-specific settings, e.g. {@code keepPrefix} for PARTIAL
 */
public record MaskingRule(
        String tablePattern,
        String columnPattern,
        MaskStrategy strategy,
        Map<String, String> options) {

    public MaskingRule {
        tablePattern = (tablePattern == null || tablePattern.isBlank()) ? "*" : tablePattern;
        columnPattern = (columnPattern == null || columnPattern.isBlank()) ? "*" : columnPattern;
        options = options == null ? Map.of() : Map.copyOf(options);
        if (strategy == null) {
            throw new IllegalArgumentException("masking rule must name a strategy");
        }
    }

    public static MaskingRule of(String tablePattern, String columnPattern, MaskStrategy strategy) {
        return new MaskingRule(tablePattern, columnPattern, strategy, Map.of());
    }

    /**
     * How tightly this rule is scoped. Higher wins when several rules match the
     * same column, so an exact {@code orders.customer_email} rule beats a
     * schema-wide {@code *.email} one.
     */
    public int specificity() {
        return (tablePattern.contains("*") ? 0 : 2) + (columnPattern.contains("*") ? 0 : 1);
    }

    public int optionInt(String key, int fallback) {
        String raw = options.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String option(String key, String fallback) {
        return options.getOrDefault(key, fallback);
    }
}
