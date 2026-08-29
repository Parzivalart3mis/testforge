package com.helios.testforge.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The PostgreSQL container the integration tests share.
 *
 * <p>One container per JVM, reused across every test class. Starting a fresh
 * one per class would multiply a 3-second startup by the number of classes for
 * no isolation benefit — the tests already isolate themselves by creating their
 * own databases and schemas.
 *
 * <p>The container's admin role is a superuser, which the platform needs
 * anyway: provisioning creates databases and roles.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {

    /** Pinned rather than floating, so a new PostgreSQL release cannot turn CI red overnight. */
    public static final DockerImageName IMAGE = DockerImageName.parse("postgres:17-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(IMAGE)
                .withDatabaseName("testforge")
                .withUsername("testforge")
                .withPassword("testforge");
    }
}
