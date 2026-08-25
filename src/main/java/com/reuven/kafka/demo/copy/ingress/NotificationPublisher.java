package com.reuven.kafka.demo.copy.ingress;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import com.reuven.kafka.demo.copy.ingress.dto.NotificationFile;
import com.reuven.kafka.demo.copy.ingress.dto.ProviderNotification;
import com.reuven.kafka.demo.copy.message.CopyMessageHeaders;
import com.reuven.kafka.demo.copy.message.RecordingCopyMessage;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Fans out one {@link RecordingCopyMessage} per recording file, all published in a single Kafka
 * transaction — every file of a notification, or none (FR-077, FR-078, research.md R9).
 */
@Component
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class NotificationPublisher {

    private final KafkaTemplate<String, RecordingCopyMessage> copyKafkaTemplate;
    private final CopyProperties properties;

    public NotificationPublisher(@Qualifier("copyKafkaTemplate") KafkaTemplate<String, RecordingCopyMessage> copyKafkaTemplate,
                                  CopyProperties properties) {
        this.copyKafkaTemplate = copyKafkaTemplate;
        this.properties = properties;
    }

    /** @return the number of messages published — one per recording file. */
    public int publish(ProviderNotification notification) {
        String sessionId = notification.payload().object().uuid();
        String accountId = notification.payload().accountId();
        String eventId = notification.eventId();
        List<NotificationFile> files = notification.payload().object().recordingFiles();
        String topic = properties.consumer().topic();

        copyKafkaTemplate.executeInTransaction(operations -> {
            for (NotificationFile file : files) {
                RecordingCopyMessage message = new RecordingCopyMessage(
                        file.id(),
                        sessionId,
                        accountId,
                        file.fileType(),
                        file.downloadUrl(),
                        file.fileSize(),
                        null,
                        file.recordingEnd());

                ProducerRecord<String, RecordingCopyMessage> record = new ProducerRecord<>(topic, file.id(), message);
                if (file.fileSize() != null) {
                    record.headers().add(CopyMessageHeaders.RECORDING_SIZE,
                            String.valueOf(file.fileSize()).getBytes(StandardCharsets.UTF_8));
                }
                record.headers().add(CopyMessageHeaders.PROVIDER_EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
                record.headers().add(CopyMessageHeaders.PROVIDER_ACCOUNT_ID, accountId.getBytes(StandardCharsets.UTF_8));

                operations.send(record);
            }
            return null;
        });

        return files.size();
    }
}
