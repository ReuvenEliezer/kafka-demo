package com.reuven.kafka.demo.copy.ingress;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signature verification (contracts/notification-ingress.openapi.yaml, research.md R10).
 *
 * <p>Two independent failure modes, both classic: {@link MessageDigest#isEqual} is used for
 * comparison because {@code String.equals} leaks, through timing, how many leading bytes matched
 * (FR-071). And including the timestamp in the signed material proves it was not <i>tampered
 * with</i>, but a captured-and-replayed notification carries a genuine signature over a genuine
 * timestamp — only the freshness range check stops replay (FR-072).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class NotificationSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "v0=";

    private final CopyProperties properties;
    private final Clock clock;

    /**
     * @param signatureHeader   the raw {@code X-Provider-Signature} header value, expected {@code v0=<hex>}
     * @param timestampHeader   the raw {@code X-Provider-Request-Timestamp} header value, Unix epoch seconds
     * @param rawBody           the exact request bytes the signature was computed over
     * @return {@link VerificationResult#VALID}, or the specific reason verification failed
     */
    public VerificationResult verify(String signatureHeader, String timestampHeader, byte[] rawBody) {
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return VerificationResult.INVALID_SIGNATURE;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException | NullPointerException e) {
            return VerificationResult.INVALID_SIGNATURE;
        }

        String candidateHex = signatureHeader.substring(SIGNATURE_PREFIX.length());
        String expectedHex = computeHex(timestampHeader, rawBody);

        byte[] candidateBytes = tryDecodeHex(candidateHex);
        byte[] expectedBytes = tryDecodeHex(expectedHex);
        if (candidateBytes == null || !MessageDigest.isEqual(candidateBytes, expectedBytes)) {
            return VerificationResult.INVALID_SIGNATURE;
        }

        if (!withinFreshnessWindow(timestamp)) {
            return VerificationResult.STALE_TIMESTAMP;
        }

        return VerificationResult.VALID;
    }

    /** Used for both notification signing and the {@code endpoint.url_validation} challenge response. */
    public String computeHex(String timestamp, byte[] rawBody) {
        String signedMaterial = "v0:" + timestamp + ":" + new String(rawBody, StandardCharsets.UTF_8);
        return hmacHex(signedMaterial.getBytes(StandardCharsets.UTF_8));
    }

    public String computeHex(String plainToken) {
        return hmacHex(plainToken.getBytes(StandardCharsets.UTF_8));
    }

    private boolean withinFreshnessWindow(long signedEpochSeconds) {
        Instant signedAt = Instant.ofEpochSecond(signedEpochSeconds);
        Duration window = properties.notification().freshnessWindow();
        Instant now = Instant.now(clock);
        return !signedAt.isBefore(now.minus(window)) && !signedAt.isAfter(now.plus(window));
    }

    private String hmacHex(byte[] material) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.notification().secret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(material));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // Deliberate, narrow exception to "no bare IllegalStateException in copy/" (see T102):
            // HmacSHA256 is a JDK-guaranteed algorithm (JCA standard names), so this is an
            // environment invariant violation, not a business error path with a meaningful domain
            // exception to reach for.
            throw new IllegalStateException("HmacSHA256 must always be available on the JVM", e);
        }
    }

    private static byte[] tryDecodeHex(String hex) {
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public enum VerificationResult {
        VALID,
        INVALID_SIGNATURE,
        STALE_TIMESTAMP
    }
}
