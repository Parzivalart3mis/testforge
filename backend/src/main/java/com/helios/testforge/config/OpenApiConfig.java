package com.helios.testforge.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** OpenAPI document served at {@code /v3/api-docs} and rendered at {@code /swagger-ui.html}. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI testForgeOpenApi(@Value("${testforge.public-url:http://localhost:8080}") String publicUrl) {
        return new OpenAPI()
                .info(new Info()
                        .title("TestForge API")
                        .version("v1")
                        .description("""
                                Self-service test data platform.

                                Register a target schema, request a dataset, and receive an ephemeral \
                                PostgreSQL database seeded with referentially consistent synthetic rows \
                                under a TTL lease. Datasets are reproducible: the same request and seed \
                                produce the same rows, including masked values.
                                """)
                        .contact(new Contact().name("Helios Test Data Platform"))
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .servers(List.of(new Server().url(publicUrl).description("TestForge service")))
                .components(new Components());
    }
}
