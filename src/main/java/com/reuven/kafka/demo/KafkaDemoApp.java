package com.reuven.kafka.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KafkaDemoApp {

    public static void main(String[] args) {
        SpringApplication.run(KafkaDemoApp.class, args);
    }

}
