package com.helios.testforge.job;

import com.helios.testforge.config.TestForgeProperties;
import com.helios.testforge.domain.job.Job;
import com.helios.testforge.domain.job.JobPhase;
import com.helios.testforge.domain.job.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Job state in DynamoDB.
 *
 * <p>Job records are written on every phase transition and polled by the console
 * every second or two while a run is in flight. That is a single-key,
 * write-heavy, short-lived access pattern with a natural expiry, which is what
 * DynamoDB with a TTL attribute is for — and specifically what we do not want
 * competing with the control-plane database for its connection pool.
 *
 * <p>The table is keyed by job id with a {@code requestedBy}-partitioned GSI for
 * the console's "my jobs" view, and {@code expiresAt} as the TTL attribute so
 * finished jobs delete themselves after the retention window without a cleanup
 * job.
 *
 * <p>The AWS SDK's low-level client is used rather than the enhanced mapper:
 * the domain model is Java records, the enhanced client's bean mapping wants
 * mutable getters and setters, and hand-mapping a handful of attributes is less
 * machinery than reshaping the domain to suit a library.
 */
@Component
@ConditionalOnProperty(name = "testforge.jobs.backend", havingValue = "dynamodb")
public class DynamoDbJobStore implements JobStore {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbJobStore.class);

    /** Global secondary index backing the per-requester listing. */
    static final String REQUESTER_INDEX = "requestedBy-createdAt-index";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoDbJobStore(DynamoDbClient dynamoDb, TestForgeProperties properties) {
        this.dynamoDb = dynamoDb;
        this.tableName = properties.jobs().tableName();
        log.info("Job state backed by DynamoDB table {}", tableName);
    }

    @Override
    public void save(Job job) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(toItem(job))
                .build());
    }

    @Override
    public Optional<Job> find(UUID id) {
        var response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("id", string(id.toString())))
                .consistentRead(true)
                .build());
        return response.hasItem() ? Optional.of(fromItem(response.item())) : Optional.empty();
    }

    @Override
    public List<Job> recent(int limit) {
        // A scan is acceptable only because the table self-expires after the
        // retention window, so it never grows past a few thousand items. The
        // per-requester view, which is the one users actually hit, uses the GSI.
        var response = dynamoDb.scan(ScanRequest.builder()
                .tableName(tableName)
                .limit(Math.max(limit * 4, 100))
                .build());
        return response.items().stream()
                .map(this::fromItem)
                .sorted(Comparator.comparing(Job::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<Job> recentFor(String requestedBy, int limit) {
        var response = dynamoDb.query(QueryRequest.builder()
                .tableName(tableName)
                .indexName(REQUESTER_INDEX)
                .keyConditionExpression("requestedBy = :who")
                .expressionAttributeValues(Map.of(":who", string(requestedBy)))
                .scanIndexForward(false)
                .limit(limit)
                .build());
        return response.items().stream().map(this::fromItem).toList();
    }

    @Override
    public Optional<Job> advance(UUID id, JobPhase phase, double fraction, String message) {
        return mutate(id, job -> InMemoryJobStore.JobUpdates.advance(job, phase, fraction, message));
    }

    @Override
    public Optional<Job> recordMetric(UUID id, String name, long value) {
        return mutate(id, job -> InMemoryJobStore.JobUpdates.withMetric(job, name, value));
    }

    @Override
    public Optional<Job> finish(UUID id, JobStatus status, String message, String error) {
        return mutate(id, job -> InMemoryJobStore.JobUpdates.finish(job, status, message, error));
    }

    @Override
    public Map<JobStatus, Long> countsByStatus() {
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        for (Job job : recent(500)) {
            counts.merge(job.status(), 1L, Long::sum);
        }
        return counts;
    }

    @Override
    public String backendName() {
        return "dynamodb:" + tableName;
    }

    /**
     * Read-modify-write. A single pipeline owns a job for its whole lifetime, so
     * there is no concurrent writer to lose an update to; the alternative — an
     * UpdateItem expression per field — would spread the transition rules across
     * two implementations instead of sharing one.
     */
    private Optional<Job> mutate(UUID id, java.util.function.UnaryOperator<Job> update) {
        Optional<Job> existing = find(id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        Job updated = update.apply(existing.get());
        save(updated);
        return Optional.of(updated);
    }

    // ------------------------------------------------------------- mapping

    private Map<String, AttributeValue> toItem(Job job) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", string(job.id().toString()));
        item.put("datasetId", string(job.datasetId().toString()));
        item.put("requestedBy", string(job.requestedBy()));
        item.put("status", string(job.status().name()));
        item.put("phase", string(job.phase().name()));
        item.put("percent", number(job.percent()));
        item.put("createdAt", number(job.createdAt().toEpochMilli()));
        putIfPresent(item, "message", job.message());
        putIfPresent(item, "error", job.error());
        if (job.startedAt() != null) {
            item.put("startedAt", number(job.startedAt().toEpochMilli()));
        }
        if (job.finishedAt() != null) {
            item.put("finishedAt", number(job.finishedAt().toEpochMilli()));
        }
        if (job.expiresAt() != null) {
            // DynamoDB's TTL attribute is epoch seconds, not milliseconds.
            item.put("expiresAt", number(job.expiresAt().getEpochSecond()));
        }
        if (!job.metrics().isEmpty()) {
            Map<String, AttributeValue> metrics = new HashMap<>();
            job.metrics().forEach((name, value) -> metrics.put(name, number(value)));
            item.put("metrics", AttributeValue.builder().m(metrics).build());
        }
        if (!job.events().isEmpty()) {
            List<AttributeValue> events = job.events().stream()
                    .map(event -> AttributeValue.builder().m(Map.of(
                            "phase", string(event.phase().name()),
                            "message", string(event.message() == null ? "" : event.message()),
                            "at", number(event.at().toEpochMilli()))).build())
                    .toList();
            item.put("events", AttributeValue.builder().l(events).build());
        }
        return item;
    }

    private Job fromItem(Map<String, AttributeValue> item) {
        Map<String, Long> metrics = new LinkedHashMap<>();
        AttributeValue rawMetrics = item.get("metrics");
        if (rawMetrics != null && rawMetrics.hasM()) {
            rawMetrics.m().forEach((name, value) -> metrics.put(name, Long.parseLong(value.n())));
        }

        List<Job.JobEvent> events = new ArrayList<>();
        AttributeValue rawEvents = item.get("events");
        if (rawEvents != null && rawEvents.hasL()) {
            for (AttributeValue event : rawEvents.l()) {
                Map<String, AttributeValue> fields = event.m();
                events.add(new Job.JobEvent(
                        JobPhase.valueOf(fields.get("phase").s()),
                        fields.get("message").s(),
                        Instant.ofEpochMilli(Long.parseLong(fields.get("at").n()))));
            }
        }

        return new Job(
                UUID.fromString(item.get("id").s()),
                UUID.fromString(item.get("datasetId").s()),
                item.get("requestedBy").s(),
                JobStatus.valueOf(item.get("status").s()),
                JobPhase.valueOf(item.get("phase").s()),
                Integer.parseInt(item.get("percent").n()),
                text(item.get("message")),
                Instant.ofEpochMilli(Long.parseLong(item.get("createdAt").n())),
                instant(item.get("startedAt")),
                instant(item.get("finishedAt")),
                text(item.get("error")),
                metrics,
                events,
                item.containsKey("expiresAt")
                        ? Instant.ofEpochSecond(Long.parseLong(item.get("expiresAt").n()))
                        : null);
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String key, String value) {
        if (value != null && !value.isBlank()) {
            item.put(key, string(value));
        }
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private static String text(AttributeValue value) {
        return value == null ? null : value.s();
    }

    private static Instant instant(AttributeValue value) {
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value.n()));
    }
}
