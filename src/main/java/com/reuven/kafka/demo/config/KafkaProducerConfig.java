package com.reuven.kafka.demo.config;

import com.reuven.kafka.demo.copy.message.RecordingCopyMessage;
import com.reuven.kafka.demo.entities.MyEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, MyEvent> producerFactory(@Value(value = "${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * {@code @Primary}: Spring Kafka's {@code @RetryableTopic}/DLT infrastructure
     * ({@link com.reuven.kafka.demo.services.KafkaConsumer}) needs exactly one unambiguous
     * {@code KafkaTemplate} bean (by raw type, ignoring generics) to publish to the DLT. Now that
     * {@code copyKafkaTemplate} exists too, this stays the one that infrastructure picks — the
     * inline strategy's behaviour is otherwise unchanged (FR-002).
     */
    @Bean
    @Primary
    public KafkaTemplate<String, MyEvent> kafkaTemplate(ProducerFactory<String, MyEvent> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * A separate transactional producer for the notification ingress (FR-078, research.md R9) — the
     * existing non-transactional {@code producerFactory}/{@code kafkaTemplate} above stay untouched
     * (FR-002). {@code transactionIdPrefix}, not a fixed {@code transactional.id}: Kafka requires each
     * live producer instance to hold a unique transactional id, and Spring's factory generates one
     * per prefix at producer-creation time.
     */
    @Bean
    @Qualifier("copyProducerFactory")
    @ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
    public ProducerFactory<String, RecordingCopyMessage> copyProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        DefaultKafkaProducerFactory<String, RecordingCopyMessage> factory = new DefaultKafkaProducerFactory<>(configProps);
        factory.setTransactionIdPrefix("copy-notification-tx-");
        return factory;
    }

    @Bean
    @Qualifier("copyKafkaTemplate")
    @ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
    public KafkaTemplate<String, RecordingCopyMessage> copyKafkaTemplate(
            @Qualifier("copyProducerFactory") ProducerFactory<String, RecordingCopyMessage> copyProducerFactory) {
        return new KafkaTemplate<>(copyProducerFactory);
    }
}