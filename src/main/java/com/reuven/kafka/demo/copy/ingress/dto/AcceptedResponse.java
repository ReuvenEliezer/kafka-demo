package com.reuven.kafka.demo.copy.ingress.dto;

public record AcceptedResponse(
        boolean accepted,
        int published
) {
}
