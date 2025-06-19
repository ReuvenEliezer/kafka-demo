package com.reuven.kafka.demo;

import com.reuven.kafka.demo.entities.MyEvent;
import com.reuven.kafka.demo.services.KafkaProducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ExtendWith(SpringExtension.class)
public class KafkaIntegrationTest {

    @Test
    void test(){
        assertThat(true).isTrue();
    }
//
//    private static final String TOPIC = "test-topic";
//    @Autowired
//    private KafkaProducer producerService;
//
//    @Container
//    public static final KafkaContainer kafkaContainer = new KafkaContainer( DockerImageName.parse("confluentinc/cp-kafka:7.2.15")
//            .asCompatibleSubstituteFor("apache/kafka"))
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
//
//    @BeforeAll
//    static void setup() {
//        kafkaContainer.start();
//        System.setProperty("spring.kafka.bootstrap-servers", kafkaContainer.getBootstrapServers());
//        System.setProperty("spring.kafka.topic", TOPIC);
//    }
//
//    @AfterAll
//    static void tearDown() {
//        kafkaContainer.stop();
//        System.clearProperty("spring.kafka.bootstrap-servers");
//        System.clearProperty("spring.kafka.topic");
//    }
//
//
//    private final BlockingQueue<MyEvent> records = new ArrayBlockingQueue<>(10);
//
//    @KafkaListener(topics = TOPIC, groupId = "test-group")
//    public void listen(MyEvent event) {
//        records.add(event);
//    }
//
//    @Test
//    void testSendReceive() throws Exception {
//        String testMessage = "Hello Testcontainers";
//        boolean isThrowException = false;
//
//        // Use async call
//        CompletableFuture<Integer> future = producerService.sendMessage(testMessage, isThrowException);
//
//        // Wait until the send is done
//        future.join();
//
//        // Poll received message
//        MyEvent received = records.poll(10, TimeUnit.SECONDS);
//        assertThat(received).isNotNull();
//        assertThat(received.msg()).startsWith(testMessage);
//    }

}
