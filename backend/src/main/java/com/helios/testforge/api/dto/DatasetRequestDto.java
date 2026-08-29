package com.helios.testforge.api.dto;

import com.helios.testforge.domain.request.DatasetRequest;
import com.helios.testforge.domain.request.MaskStrategy;
import com.helios.testforge.domain.request.MaskingPolicy;
import com.helios.testforge.domain.request.MaskingRule;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The request body for creating a dataset.
 *
 * <p>Separate from {@link DatasetRequest} so the wire format can carry
 * friendlier types — a TTL in minutes rather than an ISO-8601 duration, masking
 * rules as flat objects — and so validation annotations live at the boundary
 * rather than on the domain record.
 *
 * @param name           label for the dataset
 * @param description    optional note
 * @param requestedBy    the requesting engineer or service account
 * @param targetId       the registered target to model
 * @param schema         schema within the target; defaults to the target's own
 * @param includeTables  tables to include; empty means all
 * @param excludeTables  tables to exclude
 * @param scale          baseline rows for tables with no parent
 * @param rowOverrides   explicit per-table row counts
 * @param seed           pin a seed to reproduce an earlier dataset; omit for a fresh one
 * @param ttlMinutes     lease length in minutes
 * @param maskSensitiveByDefault whether inferred-sensitive columns mask without an explicit rule
 * @param maskingRules   per-column overrides
 * @param exportSnapshot whether to write a snapshot bundle on completion
 */
public record DatasetRequestDto(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 120) String requestedBy,
        @NotBlank String targetId,
        String schema,
        List<String> includeTables,
        List<String> excludeTables,
        @Min(1) @Max(1_000_000) Integer scale,
        Map<String, Integer> rowOverrides,
        Long seed,
        @Min(5) @Max(1440) Integer ttlMinutes,
        Boolean maskSensitiveByDefault,
        List<MaskingRuleDto> maskingRules,
        Boolean exportSnapshot) {

    public DatasetRequest toDomain(int defaultScale) {
        List<MaskingRule> rules = maskingRules == null
                ? List.of()
                : maskingRules.stream().map(MaskingRuleDto::toDomain).toList();

        MaskingPolicy policy = new MaskingPolicy(
                maskSensitiveByDefault == null || maskSensitiveByDefault, rules);

        return new DatasetRequest(
                name,
                description,
                requestedBy,
                targetId,
                schema,
                includeTables,
                excludeTables,
                scale == null ? defaultScale : scale,
                rowOverrides,
                seed == null ? 0L : seed,
                ttlMinutes == null ? null : Duration.ofMinutes(ttlMinutes),
                policy,
                exportSnapshot != null && exportSnapshot);
    }

    /**
     * A masking override.
     *
     * @param table    glob matched against the qualified table name; defaults to every table
     * @param column   glob matched against the column name; defaults to every column
     * @param strategy the transformation to apply
     * @param options  strategy options, e.g. keepPrefix for PARTIAL or percent for NUMERIC_JITTER
     */
    public record MaskingRuleDto(
            String table,
            String column,
            MaskStrategy strategy,
            Map<String, String> options) {

        public MaskingRule toDomain() {
            return new MaskingRule(table, column, strategy, options);
        }
    }
}
