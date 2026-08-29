package com.helios.testforge.api;

import com.helios.testforge.api.dto.LeaseDto;
import com.helios.testforge.lease.LeaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Managing leases on ephemeral databases. */
@RestController
@RequestMapping("/api/v1/leases")
@Tag(name = "Leases", description = "Extend, release and inspect ephemeral database leases")
public class LeaseController {

    private final LeaseService leases;

    public LeaseController(LeaseService leases) {
        this.leases = leases;
    }

    @GetMapping
    @Operation(summary = "List leases, newest first",
            description = "Connection strings are never returned here - only on issue and renewal.")
    public List<LeaseDto> list(@RequestParam(required = false) String holder,
                               @RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        var found = (holder == null || holder.isBlank())
                ? leases.list(bounded)
                : leases.listForHolder(holder, bounded);
        return found.stream().map(LeaseDto::redacted).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read one lease")
    public ResponseEntity<LeaseDto> get(@PathVariable UUID id) {
        return leases.find(id).map(LeaseDto::redacted).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-dataset/{datasetId}")
    @Operation(summary = "The lease covering a dataset")
    public ResponseEntity<LeaseDto> forDataset(@PathVariable UUID datasetId) {
        return leases.findForDataset(datasetId).map(LeaseDto::redacted).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/credentials")
    @Operation(summary = "Collect the connection string, once",
            description = """
                    Returns the full connection string for an active lease and destroys the \
                    stored copy. A second call is refused: a credential that can be fetched \
                    repeatedly ends up in every log and browser history that touched it. \
                    Provisioning is asynchronous, so this is how the requester collects the \
                    credential after the job completes.
                    """)
    public Map<String, String> claimCredentials(@PathVariable UUID id) {
        return Map.of("connectionString", leases.claimConnectionString(id));
    }

    @GetMapping("/{id}/credentials/status")
    @Operation(summary = "Whether the connection string is still waiting to be collected")
    public Map<String, Boolean> credentialStatus(@PathVariable UUID id) {
        return Map.of("unclaimed", leases.hasUnclaimedCredential(id));
    }

    @PostMapping("/{id}/renew")
    @Operation(summary = "Extend a lease",
            description = """
                    Pushes the expiry out by the configured increment, up to a per-lease renewal \
                    ceiling. Renewals cannot extend a lease indefinitely: total lifetime is capped \
                    from the moment it was issued.
                    """)
    public LeaseDto renew(@PathVariable UUID id) {
        return LeaseDto.redacted(leases.renew(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Release a lease early",
            description = "Drops the ephemeral database immediately rather than waiting for the TTL.")
    public LeaseDto release(@PathVariable UUID id) {
        return LeaseDto.redacted(leases.release(id));
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "Revoke a lease on an operator's behalf")
    public LeaseDto revoke(@PathVariable UUID id) {
        return LeaseDto.redacted(leases.revoke(id));
    }
}
