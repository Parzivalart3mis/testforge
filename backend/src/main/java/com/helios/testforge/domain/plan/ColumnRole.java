package com.helios.testforge.domain.plan;

/** What decides a column's value during seeding. */
public enum ColumnRole {

    /** The database assigns it: GENERATED ALWAYS AS, or an identity we do not override. */
    DATABASE_GENERATED,

    /** Part of the primary key; TestForge assigns it so children have keys to point at. */
    PRIMARY_KEY,

    /** Drawn from an already-seeded parent table's key pool. */
    FOREIGN_KEY,

    /** Points at an earlier row of the same table, or NULL. */
    SELF_REFERENCE,

    /** Part of a cycle-breaking edge: seeded NULL, filled by the second pass. */
    DEFERRED_FOREIGN_KEY,

    /** Points outside the dataset, so it can only be NULL. */
    DANGLING_FOREIGN_KEY,

    /** An ordinary generated value. */
    VALUE;

    /** Whether the seeder includes this column in the INSERT statement. */
    public boolean included() {
        return this != DATABASE_GENERATED;
    }
}
