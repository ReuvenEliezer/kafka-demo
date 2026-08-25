package com.reuven.kafka.demo.copy.delivery;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Deterministic destination naming, keyed by the individual recording <b>file</b> identifier, not
 * the notification or session — a notification describes several files of one session, so
 * session-level naming would collide (FR-052, research.md R15).
 *
 * <p>{@code {prefix}/{providerAccountId}/{sessionId}/{recordingFileId}}. Redelivery therefore
 * overwrites the same key, which is harmless under the at-least-once delivery this feature targets.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class ObjectKeyResolver {

    /** S3 object key length limit. */
    private static final int MAX_KEY_LENGTH = 1024;
    private static final Pattern UNSAFE_CHARACTERS = Pattern.compile("[^a-zA-Z0-9._-]");

    private final CopyProperties properties;

    public String resolve(String providerAccountId, String sessionId, String recordingFileId) {
        String key = "%s/%s/%s/%s".formatted(
                properties.destination().keyPrefix(),
                sanitize(providerAccountId),
                sanitize(sessionId),
                sanitize(recordingFileId));

        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Resolved destination key exceeds the %d character S3 limit (was %d characters)"
                            .formatted(MAX_KEY_LENGTH, key.length()));
        }
        return key;
    }

    private static String sanitize(String segment) {
        return UNSAFE_CHARACTERS.matcher(segment).replaceAll("_");
    }
}
