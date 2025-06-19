package com.reuven.kafka.demo.services;

import java.util.concurrent.CompletableFuture;

public interface KafkaProducer {
    CompletableFuture<Integer> sendMessage(String msg, boolean isThrowException);
}
