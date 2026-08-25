package com.reuven.kafka.demo.copy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * quickstart.md S7 — SC-011, SC-012 (FR-005). A dedicated inline-mode context, deliberately
 * <b>not</b> extending {@code CopyIntegrationTestBase} (which forces {@code strategy=staged}) and
 * deliberately not starting Postgres or Redis containers at all — proving the inactive strategy
 * needs neither is more convincing when they are not even offered (FR-004, T084's audit).
 *
 * <p>The behavioural contrast itself — inline pauses and accrues topic lag under an object-store
 * outage, staged keeps consuming and accrues staged backlog instead, and each delivers everything it
 * acknowledged — is already proven by two existing tests run independently rather than duplicated
 * here: {@code S3CircuitBreakerIntegrationTest} (inline) and {@code StagedConsumerIntegrationTest}
 * (staged). Re-deriving both sides of that contrast in one test would mean running a second full
 * outage-and-recovery cycle for marginal additional evidence. What this test adds is what neither of
 * those covers: a clean inline start with no staged-strategy infrastructure present at all.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "copy.consumer.strategy=inline")
class StrategyComparisonTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.1"));

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.7"))
            .withServices(LocalStackContainer.Service.S3);

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("aws.s3.region", LOCALSTACK::getRegion);
        registry.add("aws.s3.endpoint", () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3).toString());
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private S3Client s3Client;

    @Test
    @DisplayName("inline strategy starts cleanly with no staged-strategy infrastructure present at all")
    void inlineStrategyStartsCleanlyWithNoStagedInfrastructure() {
        // The context refreshing at all (via @SpringBootTest / @Autowired above) is itself proof of
        // a clean start (FR-004's "no errors"). What's checked explicitly is the absence half.
        assertThatThrownBy(() -> context.getBean("stagedBatchListener"))
                .as("no staged batch consumer bean under the inline strategy")
                .isInstanceOf(NoSuchBeanDefinitionException.class);

        assertThatThrownBy(() -> context.getBean(JdbcTemplate.class))
                .as("no DataSource/JdbcTemplate bean — JPA auto-configuration excluded entirely (FR-004)")
                .isInstanceOf(NoSuchBeanDefinitionException.class);

        assertThatThrownBy(() -> context.getBean(RedisTemplate.class))
                .as("no Redis bean — RedisAutoConfiguration excluded entirely (FR-004)")
                .isInstanceOf(NoSuchBeanDefinitionException.class);

        assertThat(context.getBean(KafkaTemplate.class)).isNotNull();
        assertThat(s3Client).isNotNull();
    }
}
