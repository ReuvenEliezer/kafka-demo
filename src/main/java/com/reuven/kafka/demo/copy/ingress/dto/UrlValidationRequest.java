package com.reuven.kafka.demo.copy.ingress.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The one-time endpoint-validation challenge issued when the endpoint is registered (FR-073).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UrlValidationRequest(
        String event,
        Payload payload
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(String plainToken) {
    }
}
