package com.helios.testforge.api;

import com.helios.testforge.generate.GeneratorResolver;
import com.helios.testforge.graph.CyclicSchemaException;
import com.helios.testforge.introspect.SchemaIntrospectionException;
import com.helios.testforge.lease.LeaseService;
import com.helios.testforge.provision.EphemeralDatabaseProvisioner;
import com.helios.testforge.seed.Seeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns failures into RFC 9457 problem details.
 *
 * <p>The messages are written for the engineer who hit them, not for a log
 * grep: a cycle that cannot be broken names the tables, an unsupported column
 * type names the column and suggests the two ways out. A test data platform
 * fails against schemas it has never seen, so the failure message is a large
 * part of whether it is usable at all.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String BASE_TYPE = "https://testforge.helios.dev/problems/";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidationFailure(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-request",
                "The request body is not valid", "One or more fields were rejected.");
        problem.setProperty("fields", fieldErrors);
        return problem;
    }

    @ExceptionHandler(CyclicSchemaException.class)
    ProblemDetail onUnbreakableCycle(CyclicSchemaException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "unbreakable-cycle",
                "The schema contains a foreign-key cycle that cannot be broken", e.getMessage());
        problem.setProperty("cycle", e.cycle().stream()
                .map(com.helios.testforge.domain.schema.TableRef::qualified).toList());
        return problem;
    }

    @ExceptionHandler(GeneratorResolver.UnsupportedColumnTypeException.class)
    ProblemDetail onUnsupportedColumn(GeneratorResolver.UnsupportedColumnTypeException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "unsupported-column-type",
                "A required column has a type no generator covers", e.getMessage());
        problem.setProperty("column", e.details().getFirst());
        problem.setProperty("type", e.details().getLast());
        return problem;
    }

    @ExceptionHandler(SchemaIntrospectionException.class)
    ProblemDetail onIntrospectionFailure(SchemaIntrospectionException e) {
        log.warn("Introspection failed: {}", e.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "introspection-failed",
                "The target schema could not be read", e.getMessage());
    }

    @ExceptionHandler(EphemeralDatabaseProvisioner.ProvisioningException.class)
    ProblemDetail onProvisioningFailure(EphemeralDatabaseProvisioner.ProvisioningException e) {
        log.error("Provisioning failed: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "provisioning-failed",
                "An ephemeral database could not be provisioned", e.getMessage());
    }

    @ExceptionHandler(Seeder.SeedingException.class)
    ProblemDetail onSeedingFailure(Seeder.SeedingException e) {
        log.error("Seeding failed: {}", e.getMessage());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "seeding-failed",
                "The dataset could not be written", e.getMessage());
    }

    @ExceptionHandler(LeaseService.LeaseException.class)
    ProblemDetail onLeaseFailure(LeaseService.LeaseException e) {
        return problem(HttpStatus.CONFLICT, "lease-conflict",
                "The lease is not in a state that allows this", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request",
                "The request could not be carried out", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail onIllegalState(IllegalStateException e) {
        log.warn("Rejected a request in an inconsistent state: {}", e.getMessage());
        return problem(HttpStatus.CONFLICT, "invalid-state",
                "The request conflicts with the current state", e.getMessage());
    }

    /**
     * Anything unforeseen. The detail is deliberately generic while the cause is
     * logged in full: an unexpected failure's message may quote a schema, a
     * query or a connection string, none of which belong in an HTTP response.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpectedFailure(Exception e) {
        log.error("Unhandled failure", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "Something went wrong",
                "The failure has been logged. Quote the timestamp when reporting it.");
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(BASE_TYPE + type));
        problem.setTitle(title);
        problem.setProperty("timestamp", java.time.Instant.now().toString());
        return problem;
    }
}
