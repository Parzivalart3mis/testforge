package com.helios.testforge.domain.request;

import com.helios.testforge.domain.schema.DataClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The masking decision procedure for one dataset.
 *
 * <p>Resolution is deny-by-default for anything introspection classified as
 * sensitive: if {@code maskSensitiveByDefault} is on and no rule says otherwise,
 * a sensitive column gets the strategy that suits its {@link DataClass} rather
 * than passing through in the clear. Explicit rules can then loosen or tighten
 * individual columns, and the most specific matching rule wins.
 *
 * @param maskSensitiveByDefault whether sensitive classes are masked without an explicit rule
 * @param rules                  overrides, evaluated most-specific-first
 */
public record MaskingPolicy(boolean maskSensitiveByDefault, List<MaskingRule> rules) {

    public MaskingPolicy {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** The default policy: mask everything classified sensitive, no overrides. */
    public static MaskingPolicy defaults() {
        return new MaskingPolicy(true, List.of());
    }

    /**
     * Chooses the strategy for one column.
     *
     * @param qualifiedTable schema-qualified table name
     * @param column         column name
     * @param dataClass      the column's inferred semantic class
     */
    public MaskStrategy strategyFor(String qualifiedTable, String column, DataClass dataClass) {
        List<MaskingRule> matches = new ArrayList<>();
        for (MaskingRule rule : rules) {
            if (matches(rule.tablePattern(), qualifiedTable) && matches(rule.columnPattern(), column)) {
                matches.add(rule);
            }
        }
        if (!matches.isEmpty()) {
            matches.sort(Comparator.comparingInt(MaskingRule::specificity).reversed());
            return matches.getFirst().strategy();
        }
        if (maskSensitiveByDefault && dataClass.sensitive()) {
            return defaultStrategyFor(dataClass);
        }
        return MaskStrategy.PRESERVE;
    }

    /** The rule that decided {@code strategyFor}, when an explicit rule matched. */
    public MaskingRule matchingRule(String qualifiedTable, String column) {
        return rules.stream()
                .filter(r -> matches(r.tablePattern(), qualifiedTable) && matches(r.columnPattern(), column))
                .max(Comparator.comparingInt(MaskingRule::specificity))
                .orElse(null);
    }

    /** The strategy that best preserves the shape of a value of this class. */
    public static MaskStrategy defaultStrategyFor(DataClass dataClass) {
        return switch (dataClass) {
            case EMAIL -> MaskStrategy.EMAIL;
            case PHONE -> MaskStrategy.PHONE;
            case GIVEN_NAME, FAMILY_NAME, FULL_NAME -> MaskStrategy.NAME;
            case SSN, NATIONAL_ID -> MaskStrategy.SSN;
            case CREDIT_CARD -> MaskStrategy.CREDIT_CARD;
            case IBAN -> MaskStrategy.IBAN;
            case DATE_OF_BIRTH -> MaskStrategy.DATE_SHIFT;
            case LATITUDE, LONGITUDE -> MaskStrategy.NUMERIC_JITTER;
            case PASSWORD_HASH, API_TOKEN -> MaskStrategy.HASH;
            case USERNAME -> MaskStrategy.TOKENIZE;
            case STREET_ADDRESS, CITY, REGION, POSTAL_CODE, IP_ADDRESS, MAC_ADDRESS -> MaskStrategy.PARTIAL;
            default -> MaskStrategy.HASH;
        };
    }

    /** Glob match supporting {@code *} as "any run of characters", case-insensitive. */
    static boolean matches(String pattern, String value) {
        if (pattern.equals("*")) {
            return true;
        }
        String p = pattern.toLowerCase(Locale.ROOT);
        String v = value.toLowerCase(Locale.ROOT);
        if (p.indexOf('*') < 0) {
            // A bare table pattern may name the table without its schema.
            return v.equals(p) || v.endsWith("." + p);
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return v.matches(regex.toString());
    }
}
