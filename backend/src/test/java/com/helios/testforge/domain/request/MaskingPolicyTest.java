package com.helios.testforge.domain.request;

import com.helios.testforge.domain.schema.DataClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingPolicyTest {

    @Test
    void masksSensitiveClassesWithoutAnyExplicitRule() {
        MaskingPolicy policy = MaskingPolicy.defaults();

        assertThat(policy.strategyFor("public.users", "email", DataClass.EMAIL))
                .isEqualTo(MaskStrategy.EMAIL);
        assertThat(policy.strategyFor("public.users", "ssn", DataClass.SSN))
                .isEqualTo(MaskStrategy.SSN);
    }

    @Test
    void leavesNonSensitiveClassesAlone() {
        assertThat(MaskingPolicy.defaults().strategyFor("public.orders", "status", DataClass.STATUS_FLAG))
                .isEqualTo(MaskStrategy.PRESERVE);
    }

    @Test
    void anExactRuleBeatsAWildcardRule() {
        MaskingPolicy policy = new MaskingPolicy(true, List.of(
                MaskingRule.of("*", "email", MaskStrategy.HASH),
                MaskingRule.of("public.orders", "email", MaskStrategy.REDACT)));

        assertThat(policy.strategyFor("public.orders", "email", DataClass.EMAIL))
                .isEqualTo(MaskStrategy.REDACT);
        assertThat(policy.strategyFor("public.users", "email", DataClass.EMAIL))
                .isEqualTo(MaskStrategy.HASH);
    }

    @Test
    void anExplicitRuleCanUnmaskASensitiveColumn() {
        MaskingPolicy policy = new MaskingPolicy(true,
                List.of(MaskingRule.of("public.support_ticket", "reporter_email", MaskStrategy.PRESERVE)));

        assertThat(policy.strategyFor("public.support_ticket", "reporter_email", DataClass.EMAIL))
                .isEqualTo(MaskStrategy.PRESERVE);
    }

    @Test
    void bareTablePatternsMatchTheUnqualifiedName() {
        MaskingPolicy policy = new MaskingPolicy(false,
                List.of(MaskingRule.of("orders", "*", MaskStrategy.HASH)));

        assertThat(policy.strategyFor("public.orders", "note", DataClass.FREE_TEXT))
                .isEqualTo(MaskStrategy.HASH);
        assertThat(policy.strategyFor("public.order_items", "note", DataClass.FREE_TEXT))
                .isEqualTo(MaskStrategy.PRESERVE);
    }

    @Test
    void globsMatchPrefixesAndSuffixes() {
        assertThat(MaskingPolicy.matches("*_email", "billing_email")).isTrue();
        assertThat(MaskingPolicy.matches("cust*", "customer_id")).isTrue();
        assertThat(MaskingPolicy.matches("cust*", "order_id")).isFalse();
        assertThat(MaskingPolicy.matches("*", "anything")).isTrue();
    }

    @Test
    void turningOffTheDefaultLeavesSensitiveColumnsUntouched() {
        MaskingPolicy policy = new MaskingPolicy(false, List.of());
        assertThat(policy.strategyFor("public.users", "ssn", DataClass.SSN))
                .isEqualTo(MaskStrategy.PRESERVE);
    }
}
