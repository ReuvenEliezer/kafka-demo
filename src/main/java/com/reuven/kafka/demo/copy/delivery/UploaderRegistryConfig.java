package com.reuven.kafka.demo.copy.delivery;

import com.reuven.kafka.demo.copy.exception.DuplicateUploaderRegistrationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link UploadPath}-keyed uploader registry from whatever {@link ObjectUploader} beans
 * exist. A silent duplicate would make the losing uploader unreachable rather than raising an error,
 * so this fails startup instead (research.md R21).
 */
@Configuration
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class UploaderRegistryConfig {

    @Bean
    public Map<UploadPath, ObjectUploader> objectUploaderRegistry(List<ObjectUploader> uploaders) {
        EnumMap<UploadPath, ObjectUploader> registry = new EnumMap<>(UploadPath.class);
        for (ObjectUploader uploader : uploaders) {
            ObjectUploader existing = registry.putIfAbsent(uploader.uploadPath(), uploader);
            if (existing != null) {
                throw new DuplicateUploaderRegistrationException(
                        "Multiple ObjectUploader beans registered for %s: %s and %s"
                                .formatted(uploader.uploadPath(), existing.getClass().getSimpleName(), uploader.getClass().getSimpleName()));
            }
        }
        return registry;
    }
}
