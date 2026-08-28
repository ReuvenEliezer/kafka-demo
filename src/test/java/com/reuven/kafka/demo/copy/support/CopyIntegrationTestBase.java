package com.reuven.kafka.demo.copy.support;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared infrastructure for the staged-strategy integration suite: Kafka, PostgreSQL, Redis, and
 * LocalStack, wired via {@link DynamicPropertySource}. Helper methods only — no shared mutable
 * fixture state, since state shared across tests is exactly what makes tests order-dependent.
 *
 * <p><b>Why {@code copy.consumer.strategy} is set via {@code @TestPropertySource}, not
 * {@code @DynamicPropertySource}</b>: {@code StrategyProfileActivator} is an
 * {@code EnvironmentPostProcessor} that must see the strategy property during environment
 * preparation, before {@code ConfigDataEnvironmentPostProcessor} loads {@code application-copy-*.yaml}.
 * {@code @DynamicPropertySource} values are injected later, via an {@code ApplicationContextInitializer}
 * that runs after environment preparation — too late to affect which profile activates, so a test
 * using it here would silently get the {@code copy-inline} profile (JPA/Redis excluded) regardless of
 * what it registers. {@code @TestPropertySource}'s inlined properties, by contrast, are applied to the
 * environment before {@code SpringApplication.run()} even starts, so the activator sees them. Anything
 * genuinely dynamic (container-derived hosts/ports) stays in {@code @DynamicPropertySource} below.
 *
 * <p>Subclasses that need a provider double add their own static {@link FakeProviderServer} field
 * and layer an additional {@code @DynamicPropertySource} on top of this class's, overriding
 * {@code copy.provider.base-url} — a subclass's registration for a key already set here wins.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "copy.consumer.strategy=staged",
        "copy.provider.allowed-hosts=localhost",
        "copy.notification.secret=0123456789abcdef0123456789abcdef"
})
public abstract class CopyIntegrationTestBase {

    protected static final String TEST_NOTIFICATION_SECRET = "0123456789abcdef0123456789abcdef";

    @Container
    protected static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.1"));

    @Container
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Container
    protected static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:8-alpine"));

    @Container
    protected static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.7"))
                    .withServices("s3");

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("aws.s3.region", LOCALSTACK::getRegion);
        registry.add("aws.s3.endpoint", () -> LOCALSTACK.getEndpoint().toString());
    }
}
