package com.reuven.kafka.demo.copy.checkpoint;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import com.reuven.kafka.demo.copy.exception.CheckpointUnavailableException;
import com.reuven.kafka.demo.copy.observability.CopyMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link CheckpointStore} over Redis (contracts/checkpoint-store.md). One hash per destination
 * object, key {@code xfer:{bucket}:{key}}. Entries live in Redis, not worker memory, so they survive
 * a full service restart (FR-027, FR-029, FR-030, FR-036).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class RedisCheckpointStore implements CheckpointStore {

    private static final String FIELD_UPLOAD_ID = "uploadId";
    private static final String FIELD_CHUNK_SIZE = "chunkSize";
    private static final String FIELD_CHUNK_COUNT = "chunkCount";
    private static final String FIELD_TOTAL_SIZE = "totalSize";
    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String PART_FIELD_PREFIX = "part:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> confirmChunkScript;
    private final CopyProperties properties;
    private final CopyMetrics metrics;
    private final Clock clock;

    @Override
    public void create(String bucket, String key, String uploadId, long chunkSize, int chunkCount, long totalSize) {
        String redisKey = redisKey(bucket, key);
        Map<String, String> metadata = new HashMap<>();
        metadata.put(FIELD_UPLOAD_ID, uploadId);
        metadata.put(FIELD_CHUNK_SIZE, String.valueOf(chunkSize));
        metadata.put(FIELD_CHUNK_COUNT, String.valueOf(chunkCount));
        metadata.put(FIELD_TOTAL_SIZE, String.valueOf(totalSize));
        metadata.put(FIELD_CREATED_AT, String.valueOf(Instant.now(clock).toEpochMilli()));

        withRedis(() -> {
            redisTemplate.opsForHash().putAll(redisKey, metadata);
            redisTemplate.expire(redisKey, properties.checkpoint().expiry());
            return null;
        });
    }

    @Override
    public boolean confirm(String bucket, String key, ChunkConfirmation confirmation) {
        String redisKey = redisKey(bucket, key);
        String field = PART_FIELD_PREFIX + confirmation.partNumber();
        String value = confirmation.etag() + "|" + confirmation.crc32c();
        String ttlSeconds = String.valueOf(properties.checkpoint().expiry().toSeconds());

        Long result = withRedis(() ->
                redisTemplate.execute(confirmChunkScript, List.of(redisKey), field, value, ttlSeconds));
        return result != null && result == 1L;
    }

    @Override
    public Optional<TransferCheckpoint> read(String bucket, String key) {
        String redisKey = redisKey(bucket, key);
        Map<Object, Object> raw = withRedis(() -> redisTemplate.opsForHash().entries(redisKey));

        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }

        String uploadId = (String) raw.get(FIELD_UPLOAD_ID);
        long chunkSize = Long.parseLong((String) raw.get(FIELD_CHUNK_SIZE));
        int chunkCount = Integer.parseInt((String) raw.get(FIELD_CHUNK_COUNT));
        long totalSize = Long.parseLong((String) raw.get(FIELD_TOTAL_SIZE));
        Instant createdAt = Instant.ofEpochMilli(Long.parseLong((String) raw.get(FIELD_CREATED_AT)));

        Map<Integer, ChunkConfirmation> confirmedChunks = new HashMap<>();
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            String fieldName = (String) entry.getKey();
            if (fieldName.startsWith(PART_FIELD_PREFIX)) {
                int partNumber = Integer.parseInt(fieldName.substring(PART_FIELD_PREFIX.length()));
                String[] parts = ((String) entry.getValue()).split("\\|", 2);
                String etag = parts[0];
                String crc32c = parts.length > 1 ? parts[1] : null;
                confirmedChunks.put(partNumber, new ChunkConfirmation(partNumber, etag, crc32c));
            }
        }

        return Optional.of(new TransferCheckpoint(uploadId, chunkSize, chunkCount, totalSize, createdAt, confirmedChunks));
    }

    @Override
    public void delete(String bucket, String key) {
        withRedis(() -> redisTemplate.delete(redisKey(bucket, key)));
    }

    private String redisKey(String bucket, String key) {
        return properties.checkpoint().keyPrefix() + ":" + bucket + ":" + key;
    }

    private <T> T withRedis(java.util.function.Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            metrics.recordCheckpointError();
            throw new CheckpointUnavailableException("Checkpoint store operation failed", e);
        }
    }
}
