package com.reuven.kafka.demo.services;

import com.reuven.kafka.demo.utils.WsAddressConstants;
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
import org.springframework.web.client.RestClient;

@Service
public class KafkaConsumer {

    private static final Logger logger = LogManager.getLogger(KafkaConsumer.class);

    private final RestClient restClient;

    public KafkaConsumer(RestClient restClient) {
        this.restClient = restClient;
    }

    @KafkaListener(
            topics = "${spring.kafka.topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @RetryableTopic(
            attempts = "3",
//            kafkaTemplate = "kafkaTemplate",
//            backoff = @org.springframework.kafka.annotation.Backoff(delay = 1000, multiplier = 2),
            dltTopicSuffix = "-dlt",
//            dltTopicSuffix = ".DLT",
            autoCreateTopics = "true"
    )
    public void listen(@Payload byte[] message,
                       @Header(KafkaHeaders.ACKNOWLEDGMENT) Acknowledgment acknowledgment,
                       @Header(KafkaHeaders.OFFSET) int offSet,
                       @Header(KafkaHeaders.RECEIVED_TOPIC) String topicName,
                       @Header(KafkaHeaders.RECEIVED_PARTITION) String partitionId) {
        logger.info("Received Message {} on topic: {}, partitionId: {} offSet={}", new String(message), topicName, partitionId, offSet);

        if (true) {
            logger.error("Simulating an error for testing purposes - this will trigger the DLT");
            throw new RuntimeException("Simulated error for testing purposes - this will trigger the DLT");
        }
        restClient.post()
                .uri(WsAddressConstants.sendPayloadUrl)
                .body(message);

        acknowledgment.acknowledge();
    }

    @DltHandler(
//            topics = "${spring.kafka.topic}" + ".DLT",
//            topics = "${spring.kafka.topic}" + "-dlt",
//            topics = "${spring.kafka.topic}",
//            groupId = "${spring.kafka.consumer.group-id}"
//            ,
//            errorHandler = "defaultErrorHandler"
    )
    public void dltListen(@Payload byte[] message,
//                          @Header(KafkaHeaders.ACKNOWLEDGMENT) Acknowledgment acknowledgment,
                          @Header(KafkaHeaders.OFFSET) int offSet,
                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topicName,
                          @Header(KafkaHeaders.RECEIVED_PARTITION) String partitionId
    ) {
        logger.warn("DLT - ########### - Received Message {} on topic: {}, partitionId: {} offSet={}",
                new String(message), topicName, partitionId, offSet
        );
    }

}
