package com.reuven.kafka.demo.services;

import com.reuven.kafka.demo.entities.MyEvent;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class KafkaProducerImpl implements KafkaProducer {

    private static final Logger logger = LogManager.getLogger(KafkaProducerImpl.class);

    private final AtomicInteger counter = new AtomicInteger(1);

    private final KafkaTemplate<String, MyEvent> kafkaTemplate;

    private final String topicName;

    public KafkaProducerImpl(KafkaTemplate<String, MyEvent> kafkaTemplate,
                             @Value(value = "${spring.kafka.topic}") String topicName) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicName = topicName;
    }

    @Override
    public CompletableFuture<Integer> sendMessage(String msg, boolean isThrowException) {
        int msgNum = counter.getAndIncrement();
        MyEvent event = new MyEvent(msg, msgNum, isThrowException);
        return kafkaTemplate.send(topicName, event)
                .thenApply(result -> {
                    RecordMetadata metadata = result.getRecordMetadata();
                    logger.info("Sent async message='{}' msgNum={} offset={} partition={}",
                            msg, msgNum, metadata.offset(), metadata.partition());
                    return msgNum;
                })
                .exceptionally(ex -> {
                    logger.error("Async send failed for msg='{}': {}", msg, ex.getMessage(), ex);
                    throw new CompletionException(ex);
                });
    }

}