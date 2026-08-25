package com.reuven.kafka.demo.config;

import com.reuven.kafka.demo.copy.message.RecordingCopyMessage;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import com.reuven.kafka.demo.entities.MyEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, Object> consumerFactory(@Value(value = "${spring.kafka.bootstrap-servers}") String bootstrapServers,
                                                           @Value(value = "${spring.kafka.consumer.group-id}") String groupId,
                                                           @Value(value = "${spring.kafka.consumer.auto-offset-reset}") String autoOffsetReset) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, Integer.MAX_VALUE);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, MyEvent.class.getPackageName());
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, MyEvent.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset); //earliest or latest
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); //TODO Remove if you want Kafka to automatically acknowledge // https://stackoverflow.com/questions/46325540/how-to-use-spring-kafkas-acknowledgement-acknowledge-method-for-manual-commit
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
//            DefaultErrorHandler defaultErrorHandler,
            ConsumerFactory<String, Object> consumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE); //TODO Remove if you want Kafka to automatically acknowledge
        factory.setConcurrency(1);
        // Retry/DLT handling for this listener is driven by @RetryableTopic on KafkaConsumer.listen(...),
        // not a CommonErrorHandler here.
//        factory.setCommonErrorHandler(defaultErrorHandler);
        return factory;
    }

//    @Bean
//    public DefaultErrorHandler defaultErrorHandler(KafkaTemplate<String, MyEvent> template,
//                                                   @Value("${spring.kafka.consumer.suffix}") String dltSuffix) {
//        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template
//                ,
//                (r, e) ->
//                        new TopicPartition(r.topic() + dltSuffix, r.partition()));
//        // retry delay = 0ms, maxAttempts = 2 (1 attempt + 1 retry)
//        FixedBackOff backOff = new FixedBackOff(1000L, 2);
//        return new DefaultErrorHandler(recoverer, backOff);
//    }

    /**
     * The staged strategy's batch listener factory (FR-006). {@code isolation.level=read_committed}
     * because the notification ingress publishes transactionally (research.md R9) — without it this
     * consumer could read messages from a transaction that later aborts.
     */
    @Bean
    @ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
    public ConsumerFactory<String, RecordingCopyMessage> copyConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${copy.consumer.group-id}") String groupId,
            @Value("${copy.consumer.batch.max-records}") int maxPollRecords,
            @Value("${copy.consumer.batch.max-wait}") Duration maxWait) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, RecordingCopyMessage.class.getPackageName());
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, RecordingCopyMessage.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, (int) maxWait.toMillis());
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    @ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
    public ConcurrentKafkaListenerContainerFactory<String, RecordingCopyMessage> batchKafkaListenerContainerFactory(
            ConsumerFactory<String, RecordingCopyMessage> copyConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, RecordingCopyMessage> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(copyConsumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setConcurrency(1);
        return factory;
    }
}
