package com.helios.testforge.api;

import com.helios.testforge.api.dto.JobDto;
import com.helios.testforge.domain.job.JobStatus;
import com.helios.testforge.job.JobStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Progress of provisioning runs. */
@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Jobs", description = "Track provisioning runs")
public class JobController {

    private final JobStore jobs;

    public JobController(JobStore jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read a job",
            description = "The console polls this while a run is in flight; percent covers the whole pipeline.")
    public ResponseEntity<JobDto> get(@PathVariable UUID id) {
        return jobs.find(id).map(JobDto::from).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(summary = "List recent jobs, newest first")
    public List<JobDto> list(@RequestParam(required = false) String requestedBy,
                             @RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        List<com.helios.testforge.domain.job.Job> found = (requestedBy == null || requestedBy.isBlank())
                ? jobs.recent(bounded)
                : jobs.recentFor(requestedBy, bounded);
        return found.stream().map(JobDto::from).toList();
    }

    @GetMapping("/counts")
    @Operation(summary = "Job counts by status")
    public Map<JobStatus, Long> counts() {
        return jobs.countsByStatus();
    }
}
