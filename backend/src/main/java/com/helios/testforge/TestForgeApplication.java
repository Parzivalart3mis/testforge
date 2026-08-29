package com.helios.testforge;

import com.helios.testforge.config.TestForgeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TestForge — self-service test data platform.
 *
 * <p>Introspects a target PostgreSQL schema, orders its foreign-key graph,
 * generates referentially consistent synthetic rows, masks PII deterministically,
 * seeds an ephemeral database and hands back a connection string under a TTL lease.
 */
@SpringBootApplication
@EnableConfigurationProperties(TestForgeProperties.class)
@EnableScheduling
@EnableAsync
public class TestForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestForgeApplication.class, args);
    }
}
