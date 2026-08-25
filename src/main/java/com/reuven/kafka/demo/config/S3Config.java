package com.reuven.kafka.demo.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
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

    /**
     * The staged delivery worker's S3 client. Retries are disabled: a multipart part streamed live
     * from the provider's socket cannot be {@code reset()}, which SDK-level retry requires. A failed
     * part falls through to the checkpoint-driven resume path instead, which already knows how to
     * restart the download at the right offset (research.md R3).
     *
     * <p>{@code requestChecksumCalculation(WHEN_REQUIRED)}: the SDK default ({@code WHEN_SUPPORTED})
     * auto-attaches its own checksum (CRC32) to every compatible request regardless of what the
     * caller specifies, which collides with the CRC32C this client explicitly requests per part
     * under {@code ChecksumType.FULL_OBJECT} — confirmed against real LocalStack as both
     * "multiple checksum types" (when we also set one explicitly) and a CRC32-vs-CRC32C mismatch
     * (when we don't). {@code WHEN_REQUIRED} makes checksums opt-in per request, as the delivery
     * path's own explicit {@code ChecksumAlgorithm.CRC32_C} calls assume.
     */
    @Bean
    @Qualifier("deliveryS3Client")
    public S3Client deliveryS3Client(@Value("${aws.s3.region}") String region,
                                      @Value("${aws.s3.endpoint:}") String endpoint) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
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
