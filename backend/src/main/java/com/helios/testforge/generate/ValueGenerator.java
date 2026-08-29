package com.helios.testforge.generate;

import java.util.SplittableRandom;

/**
 * Produces one column's value for one row.
 *
 * <p>Implementations must be pure with respect to their two inputs: given the
 * same random stream and row index they must return the same value, and they
 * must not consult a clock, a counter or any shared state. The dataset's
 * reproducibility guarantee rests entirely on that.
 *
 * <p>{@code rowIndex} is passed separately from the random stream because some
 * generators need it directly rather than as entropy — a unique column derives
 * a guaranteed-distinct value from the index instead of drawing randomly and
 * retrying on collision.
 */
@FunctionalInterface
public interface ValueGenerator {

    /**
     * @param random   a stream seeded for exactly this cell
     * @param rowIndex the zero-based row number within the table
     * @return the value, of a type the target column can hold, or null
     */
    Object generate(SplittableRandom random, int rowIndex);

    /** A generator that always yields NULL, for columns nothing can supply. */
    static ValueGenerator nullValue() {
        return (random, rowIndex) -> null;
    }

    /** A generator that yields a fixed value. */
    static ValueGenerator constant(Object value) {
        return (random, rowIndex) -> value;
    }

    /**
     * Wraps this generator so it yields NULL for roughly {@code percent} of rows.
     * Applied to nullable columns so a seeded dataset actually exercises the
     * null-handling paths that a fully populated one never would.
     */
    default ValueGenerator nullableAt(int percent) {
        if (percent <= 0) {
            return this;
        }
        return (random, rowIndex) -> random.nextInt(100) < percent ? null : generate(random, rowIndex);
    }
}
