package com.reuven.kafka.demo.services;

import com.reuven.kafka.demo.entities.MyEvent;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class KafkaProducerImpl implements KafkaProducer {

    private static final Logger logger = LogManager.getLogger(KafkaProducerImpl.class);

    private final AtomicInteger counter = new AtomicInteger(0);

    private final KafkaTemplate<String, MyEvent> kafkaTemplate;

    private final String topicName;

    public KafkaProducerImpl(KafkaTemplate<String, MyEvent> kafkaTemplate,
                             @Value(value = "${spring.kafka.topic}") String topicName) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicName = topicName;
    }

    @Override
    public Integer sendMessage(String msg, boolean isThrowException) {
        try {
            int msgNum = counter.incrementAndGet();
            RecordMetadata metadata = kafkaTemplate
                    .send(topicName, new MyEvent(msg, msgNum, isThrowException))
                    .get()
                    .getRecordMetadata();

            logger.info("Sent message=[{}] msgNum[{}] with offset=[{}] on partition {}",
                    msg, msgNum, metadata.offset(), metadata.partition());
            return msgNum;
        } catch (Exception e) {
            logger.error("failed to sending message=[{}] due to: {}", msg, e.getMessage());
            throw new RuntimeException(e);
        }
    }

}