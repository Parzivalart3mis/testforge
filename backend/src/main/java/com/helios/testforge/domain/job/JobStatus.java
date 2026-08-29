package com.helios.testforge.domain.job;

/** Terminal-or-not status of a provisioning job. */
public enum JobStatus {

    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
