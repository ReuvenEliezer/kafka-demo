package com.reuven.kafka.demo.copy;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * quickstart.md S14 — the six startup-validation cases from
 * contracts/configuration.md#startup-validation-summary. Each case binds {@link CopyProperties} in
 * isolation (no Kafka/JPA/Redis infrastructure needed) and asserts both that context refresh fails
 * and that the failure message names the offending keys and their values (FR-041).
 */
class StartupValidationTest {

    @Configuration
    @EnableConfigurationProperties(CopyProperties.class)
    static class TestConfig {
    }

    /** A complete, individually-valid property set; each test overrides only what it means to break. */
    private static ApplicationContextRunner validBaseline() {
        return new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues(
                        "copy.consumer.strategy=inline",
                        "copy.chunking.threshold=100MB",
                        "copy.chunking.base-part-size=16MB",
                        "copy.delivery.max-attempts=10",
                        "copy.delivery.max-backoff=30m",
                        "copy.checkpoint.expiry=24h",
                        "copy.cleanup.abandoned-upload-retention=7d",
                        "copy.backlog.low-water-mark=5000",
                        "copy.backlog.high-water-mark=10000",
                        "copy.provider.allowed-hosts=",
                        "copy.notification.secret=",
                        "copy.size.max-plausible-bytes=5TB",
                        "copy.destination.bucket=kafka-demo-events-test",
                        "copy.destination.key-prefix=recordings"
                );
    }

    @Test
    @DisplayName("V1: chunking.threshold above the 5 GiB single-request maximum fails the context")
    void v1ThresholdAboveMaximum() {
        validBaseline()
                .withPropertyValues("copy.chunking.threshold=6GB")
                .run(context -> {
                    Assertions.assertThat(context).hasFailed();
                    Assertions.assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("copy.chunking.threshold")
                            .hasMessageContaining("5368709120B");
                });
    }

    @Test
    @DisplayName("V2: checkpoint.expiry not exceeding the maximum retry span fails the context")
    void v2ExpiryBelowMaxRetrySpan() {
        validBaseline()
                .withPropertyValues(
                        "copy.delivery.max-attempts=3",
                        "copy.delivery.max-backoff=2s",
                        "copy.checkpoint.expiry=5s")
                .run(context -> {
                    Assertions.assertThat(context).hasFailed();
                    Assertions.assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("copy.checkpoint.expiry")
                            .hasMessageContaining("copy.delivery.max-attempts")
                            .hasMessageContaining("copy.delivery.max-backoff");
                });
    }

    @Test
    @DisplayName("V3: checkpoint.expiry not below the abandoned-upload retention fails the context")
    void v3ExpiryAboveRetention() {
        validBaseline()
                .withPropertyValues(
                        "copy.checkpoint.expiry=48h",
                        "copy.cleanup.abandoned-upload-retention=24h")
                .run(context -> {
                    Assertions.assertThat(context).hasFailed();
                    Assertions.assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("copy.checkpoint.expiry")
                            .hasMessageContaining("copy.cleanup.abandoned-upload-retention");
                });
    }

    @Test
    @DisplayName("V4: backlog.low-water-mark not below high-water-mark fails the context")
    void v4BacklogMarksInverted() {
        validBaseline()
                .withPropertyValues(
                        "copy.backlog.low-water-mark=10000",
                        "copy.backlog.high-water-mark=5000")
                .run(context -> {
                    Assertions.assertThat(context).hasFailed();
                    Assertions.assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("copy.backlog.low-water-mark")
                            .hasMessageContaining("copy.backlog.high-water-mark");
                });
    }

    @Test
    @DisplayName("V5: empty provider.allowed-hosts under the staged strategy fails the context")
    void v5EmptyAllowlistWhenStaged() {
        validBaseline()
                .withPropertyValues(
                        "copy.consumer.strategy=staged",
                        "copy.provider.allowed-hosts=",
                        "copy.notification.secret=0123456789abcdef0123456789abcdef")
                .run(context -> {
                    Assertions.assertThat(context).hasFailed();
                    Assertions.assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("copy.provider.allowed-hosts");
                });
    }

    @Test
    @DisplayName("V6: short notification.secret under the staged strategy fails the context")
    void v6ShortSecretWhenStaged() {
        validBaseline()
                .withPropertyValues(
                        "copy.consumer.strategy=staged",
                        "copy.provider.allowed-hosts=provider.example.com",
                        "copy.notification.secret=too-short")
                .run(context -> {
                    Assertions.assertThat(context).hasFailed();
                    Assertions.assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("copy.notification.secret");
                });
    }

    @Test
    @DisplayName("A fully valid staged configuration starts cleanly")
    void validStagedConfigurationStarts() {
        validBaseline()
                .withPropertyValues(
                        "copy.consumer.strategy=staged",
                        "copy.provider.allowed-hosts=provider.example.com",
                        "copy.notification.secret=0123456789abcdef0123456789abcdef")
                .run(context -> Assertions.assertThat(context).hasNotFailed());
    }
}
