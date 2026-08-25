package com.reuven.kafka.demo.copy.ingress.dto;

public record UrlValidationResponse(
        String plainToken,
        String encryptedToken
) {
}
