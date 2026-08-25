package com.reuven.kafka.demo.copy.delivery;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import com.reuven.kafka.demo.copy.provider.ProviderClient;
import com.reuven.kafka.demo.copy.provider.ProviderCredential;
import com.reuven.kafka.demo.copy.provider.RecordingMetadata;
import com.reuven.kafka.demo.copy.staging.StagedItem;
import com.reuven.kafka.demo.copy.staging.StagedItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Picks the size the chunk planner and path selector work from (FR-047–FR-050). Costs zero extra
 * calls when the declared size is present and plausible (SC-010); otherwise falls back to exactly
 * one metadata lookup per item, ever — the result is persisted, so a retry days later still costs
 * nothing (FR-049).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class SizeResolver {

    private final StagedItemRepository repository;
    private final CopyProperties properties;
    private final Clock clock;

    public long resolve(StagedItem item, ProviderClient providerClient) {
        Long resolved = item.getResolvedSizeBytes();
        if (resolved != null) {
            return resolved;
        }

        Long declared = item.getDeclaredSizeBytes();
        if (isPlausible(declared)) {
            return declared;
        }

        return lookupAndPersist(item, providerClient);
    }

    @Transactional
    long lookupAndPersist(StagedItem item, ProviderClient providerClient) {
        ProviderCredential credential = providerClient.mintDownloadCredential(item.getRecordingFileId());
        RecordingMetadata metadata = providerClient.fetchMetadata(item.getRecordingFileId(), credential);

        item.setResolvedSizeBytes(metadata.sizeBytes());
        item.setUpdatedAt(Instant.now(clock));
        repository.save(item);

        return metadata.sizeBytes();
    }

    private boolean isPlausible(Long declared) {
        return declared != null && declared >= 0 && declared <= properties.size().maxPlausibleBytes().toBytes();
    }
}
