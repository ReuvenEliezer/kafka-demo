package com.reuven.kafka.demo.controllers;

import com.reuven.kafka.demo.entities.MyEvent;
import com.reuven.kafka.demo.services.KafkaProducer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("messages")
public class MsgController {

    private static final Logger logger = LogManager.getLogger(MsgController.class);

    private final KafkaProducer kafkaProducer;

    public MsgController(KafkaProducer kafkaProducer) {
        this.kafkaProducer = kafkaProducer;
    }

    @PostMapping("/sendMessage")
    public CompletableFuture<Integer> sendMessage(@RequestBody String msg,
                                                  @RequestParam(required = false, defaultValue = "false") boolean isThrowException) {
        return kafkaProducer.sendMessage(msg, isThrowException);
    }

}