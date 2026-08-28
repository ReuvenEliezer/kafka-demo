package com.reuven.kafka.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
public class GeneralConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Spring Boot 4.1 auto-configures a Jackson 3 {@code ObjectMapper} that already disables
     * {@code WRITE_DATES_AS_TIMESTAMPS} and {@code FAIL_ON_UNKNOWN_PROPERTIES} and registers the
     * JDK 8 / java.time datatype support built into Jackson 3, so no custom mapper bean is needed.
     * Tune it via {@code spring.jackson.*} in application.yaml if that changes.
     */

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }

}
