package com.helios.testforge.generate;

import java.util.SplittableRandom;

/**
 * Derives the random seed for one cell of the dataset.
 *
 * <p>The seed for row {@code i} of {@code table.column} is a pure function of
 * the dataset seed and those three coordinates. Nothing depends on generation
 * order, thread scheduling, or how many values were drawn before it. That is
 * what lets the platform promise reproducibility while still generating tables
 * concurrently, and it is why every cell gets its own stream rather than
 * sharing one generator per table.
 *
 * <p>Mixing uses SplitMix64's finalizer, which is cheap and avalanches well
 * enough that adjacent row indices produce unrelated streams.
 */
public final class RowSeed {

    private RowSeed() {
    }

    /** Seed for a specific cell. */
    public static long forCell(long datasetSeed, String qualifiedTable, String column, long rowIndex) {
        long seed = mix(datasetSeed ^ 0x9E3779B97F4A7C15L);
        seed = mix(seed ^ hash(qualifiedTable));
        seed = mix(seed ^ hash(column));
        return mix(seed ^ (rowIndex * 0xBF58476D1CE4E5B9L));
    }

    /** Seed for a whole table, used for table-level decisions such as fanout. */
    public static long forTable(long datasetSeed, String qualifiedTable) {
        return mix(mix(datasetSeed ^ 0x94D049BB133111EBL) ^ hash(qualifiedTable));
    }

    /** A generator for one cell. */
    public static SplittableRandom randomFor(long datasetSeed, String qualifiedTable, String column, long rowIndex) {
        return new SplittableRandom(forCell(datasetSeed, qualifiedTable, column, rowIndex));
    }

    /** SplitMix64 finalizer. */
    public static long mix(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** FNV-1a over the string's UTF-16 units — stable across JVMs, unlike String.hashCode's contract in spirit. */
    static long hash(String value) {
        long hash = 0xCBF29CE484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001B3L;
        }
        return hash;
    }
}
