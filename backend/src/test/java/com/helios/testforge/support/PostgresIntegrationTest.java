package com.helios.testforge.support;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test that needs a real PostgreSQL instance.
 *
 * <p>These run against a container rather than an in-memory substitute, because
 * every interesting thing TestForge does is PostgreSQL-specific: catalog
 * introspection, identity columns, deferrable constraints, {@code jsonb},
 * {@code SET CONSTRAINTS}. An H2 compatibility mode would pass while proving
 * nothing about the code that actually ships.
 *
 * <p>They skip when no container runtime is available, so a developer without
 * Docker still gets a green unit-test run, while CI — where Docker is always
 * present — runs the full set. Skipping rather than failing is a deliberate
 * trade: the alternative is a build that is red for a reason unrelated to the
 * change being made, which trains people to ignore red builds.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf(value = "com.helios.testforge.support.DockerAvailability#isPresent",
        disabledReason = "no container runtime is available")
public @interface PostgresIntegrationTest {
}
