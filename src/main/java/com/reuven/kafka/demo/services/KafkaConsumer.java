package com.reuven.kafka.demo.services;

import com.reuven.kafka.demo.entities.MyEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
public class KafkaConsumer {

    private static final Logger logger = LogManager.getLogger(KafkaConsumer.class);

    @KafkaListener(
            topics = "${spring.kafka.topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @RetryableTopic(
            attempts = "3",
//            kafkaTemplate = "kafkaTemplate",
            backoff = @org.springframework.retry.annotation.Backoff(delay = 1000, multiplier = 2),
            dltTopicSuffix = "-dlt",
//            dltTopicSuffix = ".DLT",
            autoCreateTopics = "true"
    )
    public void listen(@Payload MyEvent msg,
                       @Header(KafkaHeaders.ACKNOWLEDGMENT) Acknowledgment acknowledgment,
                       @Header(KafkaHeaders.OFFSET) int offSet,
                       @Header(KafkaHeaders.RECEIVED_TOPIC) String topicName,
                       @Header(KafkaHeaders.RECEIVED_PARTITION) String partitionId) {
        logger.info("Received Message {} on topic: {}, partitionId: {} offSet={}", msg, topicName, partitionId, offSet);

        if (msg.isThrowException()) {
            String errorMsg = String.format("Simulating an error for testing purposes - this will trigger the DLT for message: %s", msg);
            logger.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        acknowledgment.acknowledge();
    }

    //DLT handler must be in the same class bean as the listener
    @DltHandler
    public void dltListen(@Payload MyEvent message,
                          @Header(KafkaHeaders.OFFSET) int offSet,
                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topicName,
                          @Header(KafkaHeaders.RECEIVED_PARTITION) String partitionId,
                          @Header(KafkaHeaders.EXCEPTION_MESSAGE) String errorMessage,
                          @Header(KafkaHeaders.EXCEPTION_STACKTRACE) String stackTrace
    ) {
        logger.warn("DLT - ########### - Received Message {} on topic: {}, partitionId: {} offSet={}, errorMessage: {}, stackTrace: {}",
                message, topicName, partitionId, offSet, errorMessage, stackTrace
        );
    }

// Uncomment the following method if you want to handle DLT messages in a separate method and in different class. + comment the @RetryableTopic() from consumer
// @KafkaListener(
//            topics = "${spring.kafka.topic}" + "${spring.kafka.consumer.suffix}",
//            groupId = "${spring.kafka.consumer.group-id}")
//    public void dltListen(@Payload MyEvent message,
//                          @Header(KafkaHeaders.OFFSET) int offSet,
//                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topicName,
//                          @Header(KafkaHeaders.RECEIVED_PARTITION) String partitionId) {
//        logger.warn("DLT - ########### - Received Message {} on topic: {}, partitionId: {} offSet={}",
//                message, topicName, partitionId, offSet
//        );
//    }

}
