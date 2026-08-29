package com.helios.testforge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web layer configuration.
 *
 * <p>The console is served from its own origin, so the API has to allow it
 * explicitly. Origins come from configuration rather than a wildcard: the API
 * hands out database connection strings, and an allow-all policy would let any
 * page a developer happens to have open call it with their session.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public WebConfig(@Value("${testforge.cors.allowed-origins:http://localhost:4200}") String origins) {
        this.allowedOrigins = List.of(origins.split("\\s*,\\s*"));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Location")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
