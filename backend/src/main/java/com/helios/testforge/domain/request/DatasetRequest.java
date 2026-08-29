package com.helios.testforge.domain.request;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What an engineer asked for: a dataset shaped like {@code targetId}'s schema,
 * at a given scale, masked under a given policy, leased for a given TTL.
 *
 * <p>This is the platform's unit of intent. It is captured once, stored, and
 * replayed verbatim when a dataset is regenerated — together with {@code seed}
 * that makes the whole pipeline reproducible down to the individual value.
 *
 * @param name              human label for the dataset
 * @param description       free-text note from the requester
 * @param requestedBy       the requesting engineer or service account
 * @param targetId          the registered target whose schema is being modelled
 * @param schema            schema within the target, e.g. {@code public}
 * @param includeTables     tables to include; empty means every table in the schema
 * @param excludeTables     tables to drop after {@code includeTables} is applied
 * @param scale             baseline row count for tables with no inbound dependency
 * @param rowOverrides      explicit per-table row counts, keyed by qualified or bare table name
 * @param seed              deterministic seed; {@code 0} asks the platform to derive one
 * @param ttl               how long the lease lives before the reaper drops the database
 * @param masking           masking policy applied during generation
 * @param exportSnapshot    whether to write a snapshot to object storage on completion
 */
public record DatasetRequest(
        String name,
        String description,
        String requestedBy,
        String targetId,
        String schema,
        List<String> includeTables,
        List<String> excludeTables,
        int scale,
        Map<String, Integer> rowOverrides,
        long seed,
        Duration ttl,
        MaskingPolicy masking,
        boolean exportSnapshot) {

    /** Row count used when a request does not specify a scale. */
    public static final int DEFAULT_SCALE = 100;

    /** Lease length used when a request does not specify a TTL. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(4);

    public DatasetRequest {
        includeTables = includeTables == null ? List.of() : List.copyOf(includeTables);
        excludeTables = excludeTables == null ? List.of() : List.copyOf(excludeTables);
        rowOverrides = rowOverrides == null ? Map.of() : Map.copyOf(rowOverrides);
        schema = (schema == null || schema.isBlank()) ? "public" : schema;
        scale = scale <= 0 ? DEFAULT_SCALE : scale;
        ttl = ttl == null ? DEFAULT_TTL : ttl;
        masking = masking == null ? MaskingPolicy.defaults() : masking;
    }

    /** True when the request pins a seed, making the dataset byte-for-byte reproducible. */
    public boolean hasExplicitSeed() {
        return seed != 0L;
    }
}
