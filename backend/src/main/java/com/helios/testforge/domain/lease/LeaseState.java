package com.helios.testforge.domain.lease;

/** Lifecycle of an ephemeral database lease. */
public enum LeaseState {

    /** Database exists, credentials are valid, TTL has not elapsed. */
    ACTIVE,

    /** TTL elapsed; the reaper has dropped the database and revoked the role. */
    EXPIRED,

    /** The holder gave the lease back early. The database is dropped. */
    RELEASED,

    /** An operator terminated the lease before its TTL. The database is dropped. */
    REVOKED,

    /** Provisioning or seeding failed; any partial database has been cleaned up. */
    FAILED;

    /** Whether the underlying database should still exist in this state. */
    public boolean holdsDatabase() {
        return this == ACTIVE;
    }

    /** Whether the reaper still has work to do for a lease in this state. */
    public boolean reclaimable() {
        return this == ACTIVE;
    }

    public boolean isTerminal() {
        return this != ACTIVE;
    }
}
