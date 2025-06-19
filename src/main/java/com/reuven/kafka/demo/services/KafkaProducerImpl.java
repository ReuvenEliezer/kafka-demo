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
    public Integer sendMessage(String msg, boolean isThrowException){
        try {
            RecordMetadata metadata = kafkaTemplate
                    .send(topicName, new MyEvent(msg, counter.incrementAndGet(), isThrowException))
                    .get()
                    .getRecordMetadata();

            logger.info("Sent message=[{}] with offset=[{}] on partition {}",
                    msg + counter.get(), metadata.offset(), metadata.partition());

            return counter.get();
        } catch (InterruptedException | ExecutionException ex) {
            logger.error("Unable to send message=[{}] due to: {}", msg, ex.getMessage());
            throw new RuntimeException(ex);
        }

//        ListenableFuture<SendResult<String, MyEvent>> future = kafkaTemplate.send(topicName, (msg + counter.incrementAndGet()).getBytes(StandardCharsets.UTF_8));
//
//        future.addCallback(new ListenableFutureCallback<SendResult<String, MyEvent>>() {
//
//            @Override
//            public void onSuccess(SendResult<String, MyEvent> result) {
//                logger.info("Sent message=[{}] with offset=[{}]", msg + counter.get(), result.getRecordMetadata().offset());
//            }
//
//            @Override
//            public void onFailure(Throwable ex) {
//                logger.error("Unable to send message=[{}] due to: {}", msg, ex.getMessage());
//                throw new RuntimeException(ex);
//            }
//        });
//        return counter.get();
    }

}