package com.helios.testforge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * AWS clients, created only when a backend that needs them is selected.
 *
 * <p>Credentials come from the default provider chain, which on ECS resolves to
 * the task role — so nothing here reads a key from configuration, and a
 * misconfigured environment fails at startup rather than falling back to an
 * unexpected identity.
 *
 * <p>{@code testforge.aws.endpoint} overrides the service endpoint. That exists
 * for local development against LocalStack and for integration tests, and is
 * unset in every deployed environment.
 */
@Configuration
public class AwsConfig {

    @Bean
    @ConditionalOnProperty(name = "testforge.jobs.backend", havingValue = "dynamodb")
    @ConditionalOnMissingBean
    DynamoDbClient dynamoDbClient(@Value("${testforge.aws.region:us-east-1}") String region,
                                  @Value("${testforge.aws.endpoint:}") String endpoint) {
        var builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "testforge.snapshots.backend", havingValue = "s3")
    @ConditionalOnMissingBean
    S3Client s3Client(@Value("${testforge.aws.region:us-east-1}") String region,
                      @Value("${testforge.aws.endpoint:}") String endpoint,
                      @Value("${testforge.aws.s3-path-style:false}") boolean pathStyle) {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .forcePathStyle(pathStyle);
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
