package com.reuven.kafka.demo.copy.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The atomic confirm-and-refresh operation (contracts/checkpoint-store.md, research.md R2). A plain
 * {@code HSET} followed by a separate {@code EXPIRE} leaves a crash window that records a confirmed
 * chunk under the <i>old</i>, shorter TTL — the exact mid-flight expiry FR-038 exists to prevent.
 * One Lua script collapses both into a single atomic round trip.
 */
@Configuration
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class CheckpointRedisConfig {

    /**
     * KEYS[1] = checkpoint hash key
     * ARGV[1] = field name ("part:N"), ARGV[2] = "{etag}|{crc32c}", ARGV[3] = ttl seconds
     * Returns 0 if the entry no longer exists (expired/deleted beneath this transfer), else 1.
     */
    private static final String CONFIRM_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """;

    @Bean
    public RedisScript<Long> confirmChunkScript() {
        return RedisScript.of(CONFIRM_SCRIPT, Long.class);
    }
}
