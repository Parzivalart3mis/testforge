package com.helios.testforge.api;

import com.helios.testforge.config.TestForgeProperties;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.domain.schema.TableRef;
import com.helios.testforge.graph.DependencyGraph;
import com.helios.testforge.graph.SeedOrder;
import com.helios.testforge.introspect.TargetRegistry;
import com.helios.testforge.persistence.SchemaSnapshotRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Browsing registered targets and their schemas. */
@RestController
@RequestMapping("/api/v1/targets")
@Tag(name = "Schemas", description = "Browse target schemas and their foreign-key graphs")
public class SchemaController {

    private final TargetRegistry targets;
    private final SchemaSnapshotRepository snapshots;

    public SchemaController(TargetRegistry targets, SchemaSnapshotRepository snapshots) {
        this.targets = targets;
        this.snapshots = snapshots;
    }

    @GetMapping
    @Operation(summary = "List registered targets",
            description = "Credentials are never included. Reachability is checked live.")
    public List<TargetDto> list() {
        return targets.all().stream()
                .map(target -> new TargetDto(
                        target.id(), target.displayName(), target.schema(), redactUrl(target.jdbcUrl())))
                .toList();
    }

    @GetMapping("/{targetId}/schema")
    @Operation(summary = "Read a target's schema",
            description = """
                    Returns the most recently stored snapshot. Pass refresh=true to introspect \
                    the target live, which also stores the result if the schema has drifted.
                    """)
    public ResponseEntity<SchemaSnapshot> schema(@PathVariable String targetId,
                                                 @RequestParam(required = false) String schema,
                                                 @RequestParam(defaultValue = "false") boolean refresh) {
        if (refresh) {
            SchemaSnapshot fresh = targets.introspect(targetId, schema);
            snapshots.save(targetId, fresh);
            return ResponseEntity.ok(fresh);
        }
        String effectiveSchema = schema == null || schema.isBlank()
                ? targets.require(targetId).schema()
                : schema;
        return snapshots.findLatest(targetId, effectiveSchema)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    SchemaSnapshot fresh = targets.introspect(targetId, effectiveSchema);
                    snapshots.save(targetId, fresh);
                    return ResponseEntity.ok(fresh);
                });
    }

    @PostMapping("/{targetId}/introspect")
    @Operation(summary = "Introspect a target now",
            description = """
                    Reads the target's catalog and stores the snapshot. An unchanged schema \
                    produces the same fingerprint and reuses the existing snapshot row.
                    """)
    public SchemaSnapshot introspect(@PathVariable String targetId,
                                     @RequestParam(required = false) String schema) {
        SchemaSnapshot snapshot = targets.introspect(targetId, schema);
        snapshots.save(targetId, snapshot);
        return snapshot;
    }

    @GetMapping("/{targetId}/graph")
    @Operation(summary = "The foreign-key graph and its seed order",
            description = """
                    Returns the topological order tables would be seeded in, the dependency \
                    depth of each, and any cycles that had to be broken. This is what the \
                    console renders as the schema graph.
                    """)
    public GraphDto graph(@PathVariable String targetId,
                          @RequestParam(required = false) String schema) {
        String effectiveSchema = schema == null || schema.isBlank()
                ? targets.require(targetId).schema()
                : schema;
        SchemaSnapshot snapshot = snapshots.findLatest(targetId, effectiveSchema)
                .orElseGet(() -> {
                    SchemaSnapshot fresh = targets.introspect(targetId, effectiveSchema);
                    snapshots.save(targetId, fresh);
                    return fresh;
                });

        DependencyGraph graph = DependencyGraph.of(snapshot);
        SeedOrder order = graph.order();

        List<EdgeDto> edges = graph.edges().stream()
                .map(fk -> new EdgeDto(
                        fk.child().qualified(), fk.parent().qualified(), fk.name(),
                        fk.childColumns(), fk.parentColumns(), order.isDeferred(fk)))
                .toList();

        List<NodeDto> nodes = order.order().stream()
                .map(ref -> {
                    var table = snapshot.requireTable(ref);
                    return new NodeDto(
                            ref.qualified(),
                            ref.name(),
                            order.depth().getOrDefault(ref, 0),
                            order.order().indexOf(ref),
                            table.columns().size(),
                            table.foreignKeys().size(),
                            graph.childrenOf(ref).size(),
                            (int) table.columns().stream().filter(c -> c.dataClass().sensitive()).count());
                })
                .toList();

        return new GraphDto(
                snapshot.schema(),
                snapshot.fingerprint(),
                nodes,
                edges,
                order.maxDepth(),
                order.levels().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream().map(TableRef::qualified).toList())),
                order.cycles().stream().map(cycle -> cycle.stream().map(TableRef::qualified).toList()).toList(),
                order.notes());
    }

    @GetMapping("/{targetId}/snapshots")
    @Operation(summary = "Snapshot history for a target, newest first",
            description = "A change of fingerprint between rows is schema drift.")
    public List<SchemaSnapshotRepository.SnapshotHeader> history(@PathVariable String targetId,
                                                                 @RequestParam(defaultValue = "20") int limit) {
        return snapshots.history(targetId, Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping("/{targetId}/health")
    @Operation(summary = "Whether a target is reachable")
    public Map<String, Object> health(@PathVariable String targetId) {
        TestForgeProperties.Target target = targets.require(targetId);
        return Map.of(
                "id", target.id(),
                "displayName", target.displayName(),
                "reachable", targets.isReachable(targetId));
    }

    /** Strips credentials from a JDBC URL before it leaves the service. */
    static String redactUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        int query = jdbcUrl.indexOf('?');
        return query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
    }

    /** A registered target, without credentials. */
    public record TargetDto(String id, String displayName, String schema, String jdbcUrl) {
    }

    /**
     * One table in the graph.
     *
     * @param depth          distance from the nearest root
     * @param seedOrder      position in the topological order
     * @param referencedBy   how many tables point at this one
     * @param sensitiveColumns columns classified as PII, so the console can flag them
     */
    public record NodeDto(
            String id, String name, int depth, int seedOrder,
            int columns, int foreignKeys, int referencedBy, int sensitiveColumns) {
    }

    /** One foreign key, directed child to parent. */
    public record EdgeDto(
            String from, String to, String constraint,
            List<String> fromColumns, List<String> toColumns, boolean deferred) {
    }

    /** The whole graph, plus what ordering it produces. */
    public record GraphDto(
            String schema,
            String fingerprint,
            List<NodeDto> nodes,
            List<EdgeDto> edges,
            int maxDepth,
            Map<Integer, List<String>> levels,
            List<List<String>> cycles,
            List<String> notes) {
    }
}
