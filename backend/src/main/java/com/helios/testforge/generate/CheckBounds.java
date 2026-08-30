package com.helios.testforge.generate;

import com.helios.testforge.domain.schema.CheckConstraint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the check constraints a generator can actually honour.
 *
 * <p>Solving arbitrary check expressions is a theorem-proving problem and not
 * worth attempting. Two shapes cover almost every check that appears in real
 * schemas, and both are cheap to recognise:
 *
 * <ul>
 *   <li><b>Range bounds</b> — {@code rating >= 1 AND rating <= 5}, which is how
 *       PostgreSQL normalises {@code BETWEEN}, and the bare {@code quantity > 0}
 *       that guards almost every quantity column ever written.</li>
 *   <li><b>Membership</b> — {@code status = ANY (ARRAY['A', 'B'])}, the
 *       pre-enum spelling of an enumerated column.</li>
 * </ul>
 *
 * <p>Anything else is ignored rather than guessed at. Ignoring it is safe: the
 * constraint is still created on the ephemeral database, so an unhandled check
 * fails loudly at insert time with the constraint's own name rather than
 * silently producing data that violates it.
 */
public final class CheckBounds {

    /** {@code col >= 1}, {@code "col" < (100)::numeric}, and the variants in between. */
    private static final Pattern COMPARISON = Pattern.compile(
            "\"?([A-Za-z_][A-Za-z0-9_$]*)\"?\\s*(>=|<=|<>|>|<|=)\\s*"
                    + "\\(?\\s*'?(-?\\d+(?:\\.\\d+)?)'?\\s*\\)?(?:::[A-Za-z ]+)?");

    /** {@code col = ANY (ARRAY['a'::text, 'b'::text])}. */
    private static final Pattern MEMBERSHIP = Pattern.compile(
            "\"?([A-Za-z_][A-Za-z0-9_$]*)\"?\\s*=\\s*ANY\\s*\\(\\s*ARRAY\\s*\\[(.*?)]",
            Pattern.DOTALL);

    /** A quoted literal inside an ARRAY[...], with its optional cast. */
    private static final Pattern LITERAL = Pattern.compile("'((?:[^']|'')*)'(?:::[A-Za-z ]+)?");

    private CheckBounds() {
    }

    /**
     * Interprets a table's check constraints.
     *
     * @return bounds by column name; columns with no interpretable constraint are absent
     */
    public static Map<String, Bounds> interpret(List<CheckConstraint> checks) {
        Map<String, Bounds> byColumn = new HashMap<>();

        for (CheckConstraint check : checks) {
            String expression = check.expression();
            if (expression == null) {
                continue;
            }

            Matcher membership = MEMBERSHIP.matcher(expression);
            while (membership.find()) {
                List<String> allowed = new ArrayList<>();
                Matcher literal = LITERAL.matcher(membership.group(2));
                while (literal.find()) {
                    allowed.add(literal.group(1).replace("''", "'"));
                }
                if (!allowed.isEmpty()) {
                    byColumn.merge(membership.group(1), Bounds.allowing(allowed), Bounds::combine);
                }
            }

            Matcher comparison = COMPARISON.matcher(expression);
            while (comparison.find()) {
                String column = comparison.group(1);
                String operator = comparison.group(2);
                BigDecimal operand = new BigDecimal(comparison.group(3));

                Bounds bounds = switch (operator) {
                    case ">=" -> Bounds.atLeast(operand);
                    case ">" -> Bounds.greaterThan(operand);
                    case "<=" -> Bounds.atMost(operand);
                    case "<" -> Bounds.lessThan(operand);
                    // An equality check pins the column; a not-equal one is not
                    // worth encoding, since almost any generated value satisfies it.
                    case "=" -> Bounds.exactly(operand);
                    default -> null;
                };
                if (bounds != null) {
                    byColumn.merge(column, bounds, Bounds::combine);
                }
            }
        }
        return byColumn;
    }

    /**
     * What a column's checks permit.
     *
     * @param min       inclusive lower bound, when one was found
     * @param max       inclusive upper bound, when one was found
     * @param exclusiveMin true when the lower bound came from a strict comparison
     * @param exclusiveMax true when the upper bound came from a strict comparison
     * @param allowed   permitted literal values, when the column is a membership test
     */
    public record Bounds(
            BigDecimal min,
            BigDecimal max,
            boolean exclusiveMin,
            boolean exclusiveMax,
            List<String> allowed) {

        public Bounds {
            allowed = allowed == null ? List.of() : List.copyOf(allowed);
        }

        static Bounds atLeast(BigDecimal value) {
            return new Bounds(value, null, false, false, List.of());
        }

        static Bounds greaterThan(BigDecimal value) {
            return new Bounds(value, null, true, false, List.of());
        }

        static Bounds atMost(BigDecimal value) {
            return new Bounds(null, value, false, false, List.of());
        }

        static Bounds lessThan(BigDecimal value) {
            return new Bounds(null, value, false, true, List.of());
        }

        static Bounds exactly(BigDecimal value) {
            return new Bounds(value, value, false, false, List.of());
        }

        static Bounds allowing(List<String> values) {
            return new Bounds(null, null, false, false, values);
        }

        /** Intersects two constraints on the same column: the tighter bound wins. */
        Bounds combine(Bounds other) {
            BigDecimal newMin = min;
            boolean newExclusiveMin = exclusiveMin;
            if (other.min != null && (newMin == null || other.min.compareTo(newMin) > 0)) {
                newMin = other.min;
                newExclusiveMin = other.exclusiveMin;
            }

            BigDecimal newMax = max;
            boolean newExclusiveMax = exclusiveMax;
            if (other.max != null && (newMax == null || other.max.compareTo(newMax) < 0)) {
                newMax = other.max;
                newExclusiveMax = other.exclusiveMax;
            }

            List<String> newAllowed = allowed.isEmpty() ? other.allowed : allowed;
            return new Bounds(newMin, newMax, newExclusiveMin, newExclusiveMax, newAllowed);
        }

        public boolean hasRange() {
            return min != null || max != null;
        }

        public boolean hasAllowedValues() {
            return !allowed.isEmpty();
        }

        /** The inclusive lower bound as a long, for integer columns. */
        public long minLong(long fallback) {
            if (min == null) {
                return fallback;
            }
            long value = min.setScale(0, java.math.RoundingMode.CEILING).longValue();
            return exclusiveMin ? value + 1 : value;
        }

        /** The inclusive upper bound as a long, for integer columns. */
        public long maxLong(long fallback) {
            if (max == null) {
                return fallback;
            }
            long value = max.setScale(0, java.math.RoundingMode.FLOOR).longValue();
            return exclusiveMax ? value - 1 : value;
        }

        /** The inclusive lower bound as a decimal. */
        public BigDecimal minDecimal(BigDecimal fallback) {
            return min == null ? fallback : min;
        }

        /** The inclusive upper bound as a decimal. */
        public BigDecimal maxDecimal(BigDecimal fallback) {
            return max == null ? fallback : max;
        }
    }
}
