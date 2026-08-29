package com.helios.testforge.api;

import com.helios.testforge.api.dto.DatasetRequestDto;
import com.helios.testforge.api.dto.JobDto;
import com.helios.testforge.domain.dataset.Dataset;
import com.helios.testforge.domain.dataset.DatasetSummary;
import com.helios.testforge.domain.plan.GenerationPlan;
import com.helios.testforge.persistence.AuditRepository;
import com.helios.testforge.persistence.DatasetRepository;
import com.helios.testforge.pipeline.DatasetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Requesting, previewing and inspecting datasets. */
@RestController
@RequestMapping("/api/v1/datasets")
@Tag(name = "Datasets", description = "Request synthetic datasets and inspect what was produced")
public class DatasetController {

    private final DatasetService datasets;

    public DatasetController(DatasetService datasets) {
        this.datasets = datasets;
    }

    @PostMapping
    @Operation(summary = "Request a dataset",
            description = """
                    Accepts the request and returns a job immediately; provisioning runs in the \
                    background. Poll the job for progress, then read the lease for a connection string.
                    """)
    public ResponseEntity<JobDto> request(@Valid @RequestBody DatasetRequestDto body) {
        var job = datasets.request(body.toDomain(datasets.defaultScale()));
        return ResponseEntity
                .created(URI.create("/api/v1/jobs/" + job.id()))
                .body(JobDto.from(job));
    }

    @PostMapping("/preview")
    @Operation(summary = "Plan a dataset without creating one",
            description = """
                    Introspects the target and returns the full generation plan - table order, \
                    row counts, and every masking decision with the reason it was made. Nothing \
                    is provisioned and nothing is written.
                    """)
    public GenerationPlan preview(@Valid @RequestBody DatasetRequestDto body) {
        return datasets.preview(body.toDomain(datasets.defaultScale()));
    }

    @PostMapping("/{id}/regenerate")
    @Operation(summary = "Reproduce a dataset",
            description = """
                    Replays a dataset's stored request and seed. Provided the target schema has \
                    not drifted, the result is identical value for value, masked values included.
                    """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Regeneration accepted")
    public ResponseEntity<JobDto> regenerate(@PathVariable UUID id,
                                             @RequestParam(defaultValue = "console") String requestedBy) {
        var job = datasets.regenerate(id, requestedBy);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobDto.from(job));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read one dataset, including its generation plan")
    public ResponseEntity<Dataset> get(@PathVariable UUID id) {
        return datasets.find(id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(summary = "List datasets, newest first")
    public List<DatasetSummary> list(@RequestParam(required = false) String requestedBy,
                                     @RequestParam(required = false) String targetId,
                                     @RequestParam(defaultValue = "50") int limit) {
        if (requestedBy != null && !requestedBy.isBlank()) {
            return datasets.forRequester(requestedBy, limit);
        }
        if (targetId != null && !targetId.isBlank()) {
            return datasets.forTarget(targetId, limit);
        }
        return datasets.recent(limit);
    }

    @GetMapping("/{id}/masking")
    @Operation(summary = "The masking applied to one dataset",
            description = """
                    One row per masked column, recorded at plan time. This is the record a \
                    compliance review reads, and it exists even for runs that failed partway.
                    """)
    public List<AuditRepository.MaskingRecord> masking(@PathVariable UUID id) {
        return datasets.maskingFor(id);
    }

    @GetMapping("/stats")
    @Operation(summary = "Aggregate counters for the dashboard")
    public DatasetRepository.DatasetStats stats() {
        return datasets.stats();
    }
}
