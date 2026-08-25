package com.reuven.kafka.demo.copy.consumer;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import com.reuven.kafka.demo.copy.delivery.ObjectKeyResolver;
import com.reuven.kafka.demo.copy.message.CopyMessageHeaders;
import com.reuven.kafka.demo.copy.message.RecordingCopyMessage;
import com.reuven.kafka.demo.copy.staging.StagedItem;
import com.reuven.kafka.demo.copy.staging.StagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Batched intake for the staged strategy (FR-001, FR-006). Acknowledges strictly after the staging
 * transaction commits, never inside it (FR-008, research.md R8) — acking inside
 * {@link #stageAll} would let the offset commit survive a rollback and lose the message.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class StagedBatchConsumer {

    public static final String LISTENER_ID = "stagedBatchListener";

    private final StagingService stagingService;
    private final ObjectKeyResolver keyResolver;
    private final CopyProperties properties;

    @KafkaListener(
            id = LISTENER_ID,
            topics = "${copy.consumer.topic}",
            groupId = "${copy.consumer.group-id}",
            containerFactory = "batchKafkaListenerContainerFactory"
    )
    public void listen(List<ConsumerRecord<String, RecordingCopyMessage>> records, Acknowledgment acknowledgment) {
        stageAll(records);
        acknowledgment.acknowledge();
    }

    @Transactional
    void stageAll(List<ConsumerRecord<String, RecordingCopyMessage>> records) {
        List<StagedItem> items = records.stream().map(this::toStagedItem).toList();
        stagingService.stage(items);
    }

    private StagedItem toStagedItem(ConsumerRecord<String, RecordingCopyMessage> record) {
        RecordingCopyMessage message = record.value();
        String destinationKey = keyResolver.resolve(message.providerAccountId(), message.sessionId(), message.recordingFileId());
        String providerEventId = headerValue(record, CopyMessageHeaders.PROVIDER_EVENT_ID);

        return StagedItem.builder()
                .recordingFileId(message.recordingFileId())
                .sessionId(message.sessionId())
                .providerAccountId(message.providerAccountId())
                .providerEventId(providerEventId != null ? providerEventId : "")
                .destinationBucket(properties.destination().bucket())
                .destinationKey(destinationKey)
                .declaredSizeBytes(resolveDeclaredSize(record, message))
                .contentType(message.contentType())
                .build();
    }

    /**
     * The header travels with the message so the worker never needs to deserialise it (FR-075);
     * this is only a fallback for a message published without it. Neither is validated here — an
     * implausible value is stored as-is and handled at delivery time (US6, FR-048).
     */
    private static Long resolveDeclaredSize(ConsumerRecord<String, RecordingCopyMessage> record, RecordingCopyMessage message) {
        String headerValue = headerValue(record, CopyMessageHeaders.RECORDING_SIZE);
        if (headerValue != null) {
            try {
                return Long.parseLong(headerValue);
            } catch (NumberFormatException e) {
                log.debug("Non-numeric {} header for {}: {}", CopyMessageHeaders.RECORDING_SIZE, message.recordingFileId(), headerValue);
            }
        }
        return message.declaredSizeBytes();
    }

    private static String headerValue(ConsumerRecord<String, RecordingCopyMessage> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
