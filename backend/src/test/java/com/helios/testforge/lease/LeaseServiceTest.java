package com.helios.testforge.lease;

import com.helios.testforge.config.TestForgeProperties;
import com.helios.testforge.domain.lease.Lease;
import com.helios.testforge.domain.lease.LeaseState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeaseServiceTest {

    private static LeaseService service() {
        return new LeaseService(null, null, new TestForgeProperties(
                null, null, null,
                new TestForgeProperties.Leases(
                        Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(2),
                        Duration.ofMinutes(1), Duration.ofMinutes(5)),
                null, null, List.of()));
    }

    private static Lease activeLease(Instant issuedAt, Duration ttl, int renewals) {
        return new Lease(UUID.randomUUID(), UUID.randomUUID(), "tf_demo_abc",
                "jdbc:postgresql://db:5432/tf_demo_abc", "tf_demo_abc_r", "pw",
                issuedAt, issuedAt.plus(ttl), LeaseState.ACTIVE, renewals, "tester", null);
    }

    @Test
    void aRequestedTtlAboveTheMaximumIsClamped() {
        assertThat(service().clampTtl(Duration.ofDays(30))).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void aMissingOrNonsensicalTtlFallsBackToTheDefault() {
        LeaseService service = service();
        assertThat(service.clampTtl(null)).isEqualTo(Duration.ofHours(4));
        assertThat(service.clampTtl(Duration.ZERO)).isEqualTo(Duration.ofHours(4));
        assertThat(service.clampTtl(Duration.ofHours(-3))).isEqualTo(Duration.ofHours(4));
    }

    @Test
    void aReasonableTtlIsHonouredExactly() {
        assertThat(service().clampTtl(Duration.ofHours(6))).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void theCredentialDigestIsStableAndNotThePasswordItself() {
        String digest = LeaseService.digest("hunter2");

        assertThat(digest).hasSize(64).doesNotContain("hunter2");
        assertThat(LeaseService.digest("hunter2")).isEqualTo(digest);
        assertThat(LeaseService.digest("hunter3")).isNotEqualTo(digest);
    }

    @Test
    void aLeaseStopsBeingRenewableOnceItHitsTheRenewalCeiling() {
        assertThat(activeLease(Instant.now(), Duration.ofHours(4), 0).canRenew()).isTrue();
        assertThat(activeLease(Instant.now(), Duration.ofHours(4), Lease.MAX_RENEWALS).canRenew()).isFalse();
    }

    @Test
    void aClosedLeaseIsNeverRenewable() {
        Lease released = activeLease(Instant.now(), Duration.ofHours(4), 0)
                .withState(LeaseState.RELEASED, Instant.now());

        assertThat(released.canRenew()).isFalse();
        assertThat(released.state().isTerminal()).isTrue();
        assertThat(released.state().holdsDatabase()).isFalse();
    }

    @Test
    void remainingTimeNeverGoesNegative() {
        Instant issued = Instant.now().minus(Duration.ofHours(10));
        Lease expired = activeLease(issued, Duration.ofHours(4), 0);

        assertThat(expired.isExpiredAt(Instant.now())).isTrue();
        assertThat(expired.remainingAt(Instant.now())).isEqualTo(Duration.ZERO);
    }

    @Test
    void theConnectionStringIsAvailableOnlyWhileTheCredentialIsPresent() {
        Lease issued = activeLease(Instant.now(), Duration.ofHours(4), 0);

        assertThat(issued.connectionString())
                .contains("user=tf_demo_abc_r")
                .contains("password=pw");
        assertThat(issued.withoutSecret().connectionString())
                .as("a lease read back from the control plane cannot reconstruct the password")
                .isNull();
        assertThat(issued.withoutSecret().redactedJdbcUrl())
                .isEqualTo("jdbc:postgresql://db:5432/tf_demo_abc");
    }

    @Test
    void closingALeaseDropsTheCredentialAsWellAsTheState() {
        Lease closed = activeLease(Instant.now(), Duration.ofHours(4), 0)
                .withState(LeaseState.EXPIRED, Instant.now());

        assertThat(closed.password()).isNull();
        assertThat(closed.closedAt()).isNotNull();
    }

    @Test
    void onlyAnActiveLeaseIsWorthReaping() {
        assertThat(LeaseState.ACTIVE.reclaimable()).isTrue();
        for (LeaseState state : LeaseState.values()) {
            if (state != LeaseState.ACTIVE) {
                assertThat(state.reclaimable())
                        .as("%s has already been cleaned up", state)
                        .isFalse();
            }
        }
    }
}
