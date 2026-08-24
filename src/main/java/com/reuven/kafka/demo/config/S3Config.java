package com.reuven.kafka.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.time.Duration;

@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(@Value("${aws.s3.region}") String region,
                             @Value("${aws.s3.endpoint:}") String endpoint,
                             @Value("${aws.s3.api-call-timeout}") Duration apiCallTimeout) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(apiCallTimeout)
                        .apiCallAttemptTimeout(apiCallTimeout)
                        .build());

        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .forcePathStyle(true)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        }

        return builder.build();
    }

}
