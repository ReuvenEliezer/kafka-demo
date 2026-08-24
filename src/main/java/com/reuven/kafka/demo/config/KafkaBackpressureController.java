package com.reuven.kafka.demo.config;

import com.reuven.kafka.demo.services.KafkaConsumer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

@Component
public class KafkaBackpressureController {

    private static final Logger logger = LogManager.getLogger(KafkaBackpressureController.class);

    private final KafkaListenerEndpointRegistry kafkaListenerRegistry;

    public KafkaBackpressureController(CircuitBreakerRegistry circuitBreakerRegistry,
                                       KafkaListenerEndpointRegistry kafkaListenerRegistry) {
        this.kafkaListenerRegistry = kafkaListenerRegistry;
        circuitBreakerRegistry.circuitBreaker("s3Upload")
                .getEventPublisher()
                .onStateTransition(this::handleStateTransition);
    }

    private void handleStateTransition(CircuitBreakerOnStateTransitionEvent event) {
        MessageListenerContainer container = kafkaListenerRegistry.getListenerContainer(KafkaConsumer.LISTENER_ID);
        if (container == null) {
            logger.warn("Kafka listener container '{}' not found", KafkaConsumer.LISTENER_ID);
            return;
        }

        switch (event.getStateTransition()) {
            case CLOSED_TO_OPEN, HALF_OPEN_TO_OPEN -> {
                logger.error("S3 network instability detected - circuit breaker {}. Pausing Kafka consumer", event.getStateTransition());
                container.pause();
            }
            case OPEN_TO_HALF_OPEN, HALF_OPEN_TO_CLOSED -> {
                if (container.isContainerPaused()) {
                    logger.info("S3 network recovering - circuit breaker {}. Resuming Kafka consumer", event.getStateTransition());
                    container.resume();
                }
            }
            default -> { }
        }
    }

}
