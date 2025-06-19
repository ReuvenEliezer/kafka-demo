//package com.reuven.kafka.demo;
//
//import com.reuven.kafka.demo.services.KafkaProducer;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.kafka.annotation.KafkaListener;
//import org.springframework.kafka.test.context.EmbeddedKafka;
//import org.springframework.test.context.junit.jupiter.SpringExtension;
//import org.testcontainers.containers.KafkaContainer;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//
//import java.util.concurrent.ArrayBlockingQueue;
//import java.util.concurrent.BlockingQueue;
//import java.util.concurrent.TimeUnit;
//
//import static org.assertj.core.api.Assertions.assertThat;
//
//@SpringBootTest
//@Testcontainers
//@ExtendWith(SpringExtension.class)
//public class KafkaIntegrationTest {
//
//    private static final String TOPIC = "test-topic";
//
//    @Container
//    public static KafkaContainer kafkaContainer = new KafkaContainer("confluentinc/cp-kafka:7.6.0")
//            .withEnv("KAFKA_ENABLE_KRAFT", "yes")
//            .withEnv("KAFKA_CFG_NODE_ID", "1")
//            .withEnv("KAFKA_CFG_PROCESS_ROLES", "broker,controller")
//            .withEnv("KAFKA_CFG_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
//            .withEnv("KAFKA_CFG_LISTENERS", "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093")
//            .withEnv("KAFKA_CFG_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:9092")
//            .withEnv("KAFKA_CFG_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
//            .withEnv("KAFKA_CFG_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
//            .withEnv("ALLOW_PLAINTEXT_LISTENER", "yes");
//
//    @Autowired
//    private KafkaProducer producerService;
//
//    private final BlockingQueue<String> records = new ArrayBlockingQueue<>(10);
//
//    @KafkaListener(topics = TOPIC, groupId = "test-group")
//    public void listen(String message) {
//        records.add(message);
//    }
//
//    @Test
//    void testSendReceive() throws InterruptedException {
//        // Use kafkaContainer bootstrap servers for config override if needed
//        String testMessage = "Hello Testcontainers";
//
//        producerService.sendMessage(testMessage);
//
//        String received = records.poll(10, TimeUnit.SECONDS);
//        assertThat(received).isEqualTo(testMessage);
//    }
//}
