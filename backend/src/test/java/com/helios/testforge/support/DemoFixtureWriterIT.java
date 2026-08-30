package com.helios.testforge.support;

import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.introspect.PostgresSchemaIntrospector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writes the console's demo schema fixture from the real introspector.
 *
 * <p>The browser demo needs a schema to work against, and hand-writing one in
 * TypeScript would guarantee it drifts from what introspection actually
 * produces — a wrong enum ordering or a missed identity flag in the fixture
 * would make the demo quietly misrepresent the engine. Generating it here means
 * the fixture is, by construction, exactly what the service would return.
 *
 * <p>Off by default: it writes into another module's source tree, which is not
 * something a normal test run should do. Regenerate deliberately after changing
 * the demo schema:
 *
 * <pre>{@code
 * ./mvnw verify -Dit.test=DemoFixtureWriterIT -Dtestforge.writeDemoFixture=true
 * }</pre>
 */
@EnabledIf(value = "com.helios.testforge.support.DockerAvailability#isPresent",
        disabledReason = "no container runtime is available")
@EnabledIfSystemProperty(named = "testforge.writeDemoFixture", matches = "true",
        disabledReason = "regenerating the console fixture is a deliberate action")
class DemoFixtureWriterIT {

    private static final Path TARGET =
            Path.of("..", "console", "src", "app", "demo", "demo-schema.json");

    @Test
    void writesTheDemoSchemaFixtureForTheConsole() throws Exception {
        SchemaSnapshot snapshot = new PostgresSchemaIntrospector()
                .introspect(DemoSchema.dataSource(), DemoSchema.SCHEMA);

        assertThat(snapshot.tableCount()).isEqualTo(22);

        ObjectMapper mapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();

        Path resolved = TARGET.toAbsolutePath().normalize();
        Files.createDirectories(resolved.getParent());
        Files.writeString(resolved, mapper.writeValueAsString(snapshot));

        System.out.println("Wrote demo fixture: " + resolved + " ("
                + Files.size(resolved) / 1024 + " KB, " + snapshot.tableCount() + " tables)");
    }
}
