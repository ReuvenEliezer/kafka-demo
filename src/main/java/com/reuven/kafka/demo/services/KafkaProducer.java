package com.reuven.kafka.demo.services;

public interface KafkaProducer {
    Integer sendMessage(String msg, boolean isThrowException);
}
