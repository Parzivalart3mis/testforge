package com.helios.testforge.mask;

import com.helios.testforge.domain.request.MaskStrategy;
import com.helios.testforge.domain.request.MaskingRule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingEngineTest {

    private final MaskingEngine engine = MaskingEngine.withKey("test-key-one");
    private final MaskContext context = MaskContext.forDataset("dataset-a");

    private String mask(MaskStrategy strategy, String value) {
        return engine.maskText(strategy, value, context, null);
    }

    @Nested
    class Determinism {

        @Test
        void theSameInputAlwaysProducesTheSameOutput() {
            for (MaskStrategy strategy : MaskStrategy.values()) {
                String first = engine.maskText(strategy, "alice@corp.example", context, null);
                for (int i = 0; i < 10; i++) {
                    assertThat(engine.maskText(strategy, "alice@corp.example", context, null))
                            .as("%s must be deterministic", strategy)
                            .isEqualTo(first);
                }
            }
        }

        @Test
        void theSameValueMasksIdenticallyAcrossColumnsSoJoinsSurvive() {
            // users.email and audit_log.actor_email hold the same address.
            String inUsers = engine.maskText(MaskStrategy.EMAIL, "dana@corp.example", context, null);
            String inAuditLog = engine.maskText(MaskStrategy.EMAIL, "dana@corp.example", context, null);

            assertThat(inUsers).isEqualTo(inAuditLog);
        }

        @Test
        void aDifferentDatasetProducesDifferentValuesSoDatasetsCannotBeCorrelated() {
            String inA = engine.maskText(MaskStrategy.EMAIL, "dana@corp.example",
                    MaskContext.forDataset("dataset-a"), null);
            String inB = engine.maskText(MaskStrategy.EMAIL, "dana@corp.example",
                    MaskContext.forDataset("dataset-b"), null);

            assertThat(inA).isNotEqualTo(inB);
        }

        @Test
        void aDifferentKeyProducesDifferentValues() {
            String withKeyOne = engine.maskText(MaskStrategy.HASH, "secret", context, null);
            String withKeyTwo = MaskingEngine.withKey("test-key-two")
                    .maskText(MaskStrategy.HASH, "secret", context, null);

            assertThat(withKeyOne).isNotEqualTo(withKeyTwo);
        }

        @Test
        void settingAColumnScopeDeliberatelyBreaksCrossColumnLinkage() {
            MaskContext scoped = context.withColumn("public.users.email");
            assertThat(engine.maskText(MaskStrategy.EMAIL, "dana@corp.example", scoped, null))
                    .isNotEqualTo(engine.maskText(MaskStrategy.EMAIL, "dana@corp.example", context, null));
        }

        @Test
        void nullPassesThroughEveryStrategy() {
            for (MaskStrategy strategy : MaskStrategy.values()) {
                assertThat(engine.mask(strategy, null, context, null))
                        .as("%s must leave null alone", strategy)
                        .isNull();
            }
        }
    }

    @Nested
    class FormatPreservation {

        @Test
        void aMaskedEmailIsStillAValidAddressAtAReservedDomain() {
            String masked = mask(MaskStrategy.EMAIL, "dana.smith@acmecorp.com");

            assertThat(masked).matches("[a-z]+\\.[a-z]+[0-9]+@[a-z.]*example\\.(com|net|org)");
            assertThat(masked).doesNotContain("acmecorp");
            assertThat(masked).doesNotContain("dana");
        }

        @Test
        void theOriginalDomainCanBeKeptWhenItIsItselfUnderTest() {
            MaskingRule rule = new MaskingRule("*", "email", MaskStrategy.EMAIL,
                    Map.of("preserveDomain", "true"));

            assertThat(engine.maskText(MaskStrategy.EMAIL, "dana@acmecorp.com", context, rule))
                    .endsWith("@acmecorp.com");
        }

        @Test
        void aMaskedPhoneKeepsItsPunctuationAndLength() {
            String masked = mask(MaskStrategy.PHONE, "+1 (555) 867-5309");

            assertThat(masked).hasSameSizeAs("+1 (555) 867-5309");
            assertThat(masked).matches("\\+\\d \\(\\d{3}\\) \\d{3}-\\d{4}");
            assertThat(masked).isNotEqualTo("+1 (555) 867-5309");
        }

        @Test
        void aMaskedSsnLandsInTheReservedRangeSoItCannotBeARealNumber() {
            String masked = mask(MaskStrategy.SSN, "123-45-6789");

            assertThat(masked).matches("\\d{3}-\\d{2}-\\d{4}");
            int area = Integer.parseInt(masked.substring(0, 3));
            assertThat(area)
                    .as("900-999 is reserved and never issued")
                    .isBetween(900, 999);
            assertThat(masked.substring(4, 6)).isNotEqualTo("00");
            assertThat(masked.substring(7)).isNotEqualTo("0000");
        }

        @Test
        void anSsnWithoutPunctuationStaysWithoutPunctuation() {
            assertThat(mask(MaskStrategy.SSN, "123456789")).matches("\\d{9}");
        }

        @Test
        void aMaskedCardNumberStillPassesLuhn() {
            String masked = mask(MaskStrategy.CREDIT_CARD, "4111111111111111");

            assertThat(Checksums.isLuhnValid(masked))
                    .as("application code validates cards before anything else; an invalid one only tests the error path")
                    .isTrue();
            assertThat(masked).hasSize(16).startsWith("4");
            assertThat(masked).isNotEqualTo("4111111111111111");
        }

        @Test
        void aMaskedCardKeepsItsGroupingSeparators() {
            String masked = mask(MaskStrategy.CREDIT_CARD, "4111-1111-1111-1111");

            assertThat(masked).matches("\\d{4}-\\d{4}-\\d{4}-\\d{4}");
            assertThat(Checksums.isLuhnValid(masked)).isTrue();
        }

        @Test
        void aMaskedIbanStillPassesItsMod97Checksum() {
            String masked = mask(MaskStrategy.IBAN, "GB82WEST12345698765432");

            assertThat(Checksums.isIbanValid(masked)).isTrue();
            assertThat(masked).startsWith("GB").hasSize("GB82WEST12345698765432".length());
            assertThat(masked).isNotEqualTo("GB82WEST12345698765432");
        }

        @Test
        void partialMaskingKeepsTheRequestedEdgesAndTheLength() {
            MaskingRule rule = new MaskingRule("*", "*", MaskStrategy.PARTIAL,
                    Map.of("keepPrefix", "2", "keepSuffix", "3"));
            String original = "SW1A 2AA London";
            String masked = engine.maskText(MaskStrategy.PARTIAL, original, context, rule);

            assertThat(masked).hasSameSizeAs(original);
            assertThat(masked).startsWith("SW").endsWith("don");
            assertThat(masked).isNotEqualTo(original);
        }

        @Test
        void partialMaskingOnAValueTooShortToRevealFallsBackToFullReplacement() {
            MaskingRule rule = new MaskingRule("*", "*", MaskStrategy.PARTIAL,
                    Map.of("keepPrefix", "5", "keepSuffix", "5"));

            assertThat(engine.maskText(MaskStrategy.PARTIAL, "ab", context, rule)).hasSize(2);
        }

        @Test
        void aMaskedNameKeepsWhetherItWasSingleOrFull() {
            assertThat(mask(MaskStrategy.NAME, "Dana")).doesNotContain(" ");
            assertThat(mask(MaskStrategy.NAME, "Dana Smith")).contains(" ");
        }
    }

    @Nested
    class Uniqueness {

        @Test
        void distinctEmailsMaskToDistinctValuesSoAUniqueConstraintSurvives() {
            Set<String> masked = new HashSet<>();
            for (int i = 0; i < 5_000; i++) {
                masked.add(mask(MaskStrategy.EMAIL, "user" + i + "@corp.example"));
            }

            assertThat(masked)
                    .as("collisions here would abort seeding on a UNIQUE constraint")
                    .hasSize(5_000);
        }

        @Test
        void distinctValuesHashToDistinctDigests() {
            Set<String> masked = new HashSet<>();
            for (int i = 0; i < 5_000; i++) {
                masked.add(mask(MaskStrategy.HASH, "value-" + i));
            }
            assertThat(masked).hasSize(5_000);
        }
    }

    @Nested
    class CorrelatedValues {

        @Test
        void everyDateInARowShiftsByTheSameOffsetSoIntervalsSurvive() {
            MaskContext row = context.withRow("customer:42");
            LocalDate signup = LocalDate.of(2026, 1, 10);
            LocalDate firstOrder = LocalDate.of(2026, 1, 13);

            LocalDate maskedSignup = (LocalDate) engine.mask(MaskStrategy.DATE_SHIFT, signup, row, null);
            LocalDate maskedFirstOrder = (LocalDate) engine.mask(MaskStrategy.DATE_SHIFT, firstOrder, row, null);

            assertThat(java.time.temporal.ChronoUnit.DAYS.between(maskedSignup, maskedFirstOrder))
                    .as("an order placed three days after signup must stay three days after signup")
                    .isEqualTo(3);
            assertThat(maskedSignup).isNotEqualTo(signup);
        }

        @Test
        void differentRowsShiftByDifferentOffsets() {
            LocalDate date = LocalDate.of(2026, 6, 1);
            Set<Object> shifted = new HashSet<>();
            for (int i = 0; i < 50; i++) {
                shifted.add(engine.mask(MaskStrategy.DATE_SHIFT, date, context.withRow("row:" + i), null));
            }
            assertThat(shifted).hasSizeGreaterThan(20);
        }

        @Test
        void theShiftStaysWithinTheConfiguredBound() {
            MaskingRule rule = new MaskingRule("*", "*", MaskStrategy.DATE_SHIFT, Map.of("maxDays", "30"));
            LocalDate date = LocalDate.of(2026, 6, 1);

            for (int i = 0; i < 200; i++) {
                LocalDate shifted = (LocalDate) engine.mask(
                        MaskStrategy.DATE_SHIFT, date, context.withRow("row:" + i), rule);
                assertThat(Math.abs(java.time.temporal.ChronoUnit.DAYS.between(date, shifted)))
                        .isLessThanOrEqualTo(30);
            }
        }
    }

    @Nested
    class NumericJitter {

        @Test
        void jitterStaysWithinTheConfiguredPercentageAndKeepsScale() {
            MaskingRule rule = new MaskingRule("*", "*", MaskStrategy.NUMERIC_JITTER, Map.of("percent", "10"));
            BigDecimal original = new BigDecimal("1250.00");

            for (int i = 0; i < 100; i++) {
                BigDecimal jittered = (BigDecimal) engine.mask(
                        MaskStrategy.NUMERIC_JITTER, original, context.withRow("r" + i), rule);

                assertThat(jittered.scale()).isEqualTo(2);
                assertThat(jittered.doubleValue()).isBetween(1125.0, 1375.0);
            }
        }

        @Test
        void jitterPreservesTypeAcrossNumericJavaTypes() {
            assertThat(engine.mask(MaskStrategy.NUMERIC_JITTER, 100, context, null)).isInstanceOf(Integer.class);
            assertThat(engine.mask(MaskStrategy.NUMERIC_JITTER, 100L, context, null)).isInstanceOf(Long.class);
            assertThat(engine.mask(MaskStrategy.NUMERIC_JITTER, 100.0d, context, null)).isInstanceOf(Double.class);
            assertThat(engine.mask(MaskStrategy.NUMERIC_JITTER, new BigDecimal("1.5"), context, null))
                    .isInstanceOf(BigDecimal.class);
        }
    }

    @Nested
    class SimpleStrategies {

        @Test
        void redactUsesTheConfiguredToken() {
            assertThat(mask(MaskStrategy.REDACT, "anything")).isEqualTo("[REDACTED]");
        }

        @Test
        void tokenizeProducesAStableOpaqueJoinKey() {
            String token = mask(MaskStrategy.TOKENIZE, "acct-991");
            assertThat(token).startsWith("tok_").hasSize(20);
            assertThat(mask(MaskStrategy.TOKENIZE, "acct-991")).isEqualTo(token);
        }

        @Test
        void preserveLeavesTheValueAlone() {
            assertThat(mask(MaskStrategy.PRESERVE, "keep me")).isEqualTo("keep me");
        }

        @Test
        void nullifyReturnsNullEvenForANonNullInput() {
            assertThat(engine.mask(MaskStrategy.NULLIFY, "drop me", context, null)).isNull();
        }

        @Test
        void hashLengthIsConfigurableWithinSaneBounds() {
            MaskingRule rule = new MaskingRule("*", "*", MaskStrategy.HASH, Map.of("length", "12"));
            assertThat(engine.maskText(MaskStrategy.HASH, "x", context, rule)).hasSize(12);

            MaskingRule absurd = new MaskingRule("*", "*", MaskStrategy.HASH, Map.of("length", "9999"));
            assertThat(engine.maskText(MaskStrategy.HASH, "x", context, absurd)).hasSize(64);
        }
    }
}
