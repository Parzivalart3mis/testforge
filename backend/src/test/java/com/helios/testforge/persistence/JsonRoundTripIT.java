package com.helios.testforge.persistence;

import com.helios.testforge.domain.plan.ColumnPlan;
import com.helios.testforge.domain.plan.ColumnRole;
import com.helios.testforge.domain.plan.GenerationPlan;
import com.helios.testforge.domain.plan.TablePlan;
import com.helios.testforge.domain.request.DatasetRequest;
import com.helios.testforge.domain.request.MaskStrategy;
import com.helios.testforge.domain.request.MaskingPolicy;
import com.helios.testforge.domain.request.MaskingRule;
import com.helios.testforge.domain.schema.DataClass;
import com.helios.testforge.domain.schema.TableRef;
import com.helios.testforge.support.PostgresIntegrationTest;
import com.helios.testforge.support.TestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requests and plans survive a round trip through JSONB.
 *
 * <p>This is not incidental coverage. A dataset's reproducibility rests on the
 * stored request and plan coming back byte-equivalent months later: if a
 * {@code Duration} or an enum fails to deserialise, the failure surfaces as a
 * dataset that cannot be regenerated, long after the change that caused it.
 *
 * <p>It also pins the date format. Spring Boot 4 moved to Jackson 3, where
 * {@code WRITE_DATES_AS_TIMESTAMPS} is no longer a {@code SerializationFeature},
 * so the override Jackson 2 needed is gone — this asserts the resulting
 * behaviour rather than trusting it.
 */
@PostgresIntegrationTest
@Import(TestContainersConfig.class)
class JsonRoundTripIT {

    @Autowired
    Json json;

    @Test
    void temporalValuesSerialiseAsIsoStringsRatherThanEpochNumbers() {
        String serialised = json.toJsonString(Map.of("at", Instant.parse("2026-03-04T05:06:07Z")));

        assertThat(serialised)
                .as("a numeric timestamp here would silently change every stored payload's shape")
                .contains("2026-03-04T05:06:07Z");
    }

    @Test
    void aDatasetRequestSurvivesTheRoundTrip() {
        DatasetRequest original = new DatasetRequest(
                "checkout fixtures",
                "for the refund regression",
                "someone@example.com",
                "demo-commerce",
                "public",
                List.of("public.order_header", "public.order_line"),
                List.of("public.audit_log"),
                250,
                Map.of("public.customer", 40),
                987654321L,
                Duration.ofHours(6),
                new MaskingPolicy(true, List.of(
                        MaskingRule.of("*", "email", MaskStrategy.EMAIL),
                        new MaskingRule("public.customer", "phone", MaskStrategy.PARTIAL,
                                Map.of("keepPrefix", "3")))),
                true);

        DatasetRequest restored = json.fromJson(json.toJsonString(original), DatasetRequest.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.ttl()).isEqualTo(Duration.ofHours(6));
        assertThat(restored.masking().rules()).hasSize(2);
        assertThat(restored.masking().rules().getLast().options()).containsEntry("keepPrefix", "3");
    }

    @Test
    void aGenerationPlanSurvivesTheRoundTrip() {
        GenerationPlan original = new GenerationPlan(
                42L,
                "public",
                "abc123def456",
                List.of(new TablePlan(
                        TableRef.of("public", "customer"),
                        0, 0, 100,
                        List.of(
                                new ColumnPlan("id", "integer", DataClass.SURROGATE_KEY,
                                        ColumnRole.PRIMARY_KEY, "surrogate_key",
                                        MaskStrategy.PRESERVE, "key column", true, false, null, null),
                                new ColumnPlan("email", "character varying(255)", DataClass.EMAIL,
                                        ColumnRole.VALUE, "email",
                                        MaskStrategy.EMAIL, "sensitive class EMAIL", true, false, null, null)),
                        List.of(),
                        List.of())),
                100L,
                1,
                List.of("Broke a foreign-key cycle by deferring fk_customer_primary_order_id"));

        GenerationPlan restored = json.fromJson(json.toJsonString(original), GenerationPlan.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.tables().getFirst().table()).isEqualTo(TableRef.of("public", "customer"));
        assertThat(restored.maskingSummary()).hasSize(1);
        assertThat(restored.warnings()).hasSize(1);
    }

    @Test
    void nullPayloadsAreTolerated() {
        assertThat(json.toJsonString(null)).isNull();
        assertThat(json.fromJson(null, DatasetRequest.class)).isNull();
        assertThat(json.fromJson("", DatasetRequest.class)).isNull();
        assertThat(json.toJsonb(null).getValue()).isNull();
    }

    @Test
    void aJsonbParameterCarriesTheCorrectPostgresType() {
        assertThat(json.toJsonb(Map.of("a", 1)).getType()).isEqualTo("jsonb");
    }
}
