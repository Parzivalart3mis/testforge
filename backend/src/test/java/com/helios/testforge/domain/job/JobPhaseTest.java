package com.helios.testforge.domain.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobPhaseTest {

    @Test
    void progressIsMonotonicAcrossPhases() {
        int previous = -1;
        for (JobPhase phase : JobPhase.values()) {
            int percent = phase.overallPercent(0d);
            assertThat(percent)
                    .as("phase %s starts at or after the previous phase", phase)
                    .isGreaterThanOrEqualTo(previous);
            previous = percent;
        }
    }

    @Test
    void theQueuedPhaseStartsAtZeroAndTheFinalPhaseReachesOneHundred() {
        assertThat(JobPhase.QUEUED.overallPercent(0d)).isZero();
        assertThat(JobPhase.DONE.overallPercent(1d)).isEqualTo(100);
    }

    @Test
    void progressWithinAPhaseInterpolates() {
        int start = JobPhase.GENERATING.overallPercent(0d);
        int half = JobPhase.GENERATING.overallPercent(0.5d);
        int end = JobPhase.GENERATING.overallPercent(1d);

        assertThat(half).isGreaterThan(start).isLessThan(end);
    }

    @Test
    void fractionsOutsideTheUnitIntervalAreClamped() {
        assertThat(JobPhase.SEEDING.overallPercent(-5d)).isEqualTo(JobPhase.SEEDING.overallPercent(0d));
        assertThat(JobPhase.SEEDING.overallPercent(9d)).isEqualTo(JobPhase.SEEDING.overallPercent(1d));
    }
}
