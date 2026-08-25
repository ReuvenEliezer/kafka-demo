package com.reuven.kafka.demo.copy.checkpoint;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Surfaces Redis reachability under {@code /actuator/health} (FR-058). Sustained Redis unavailability
 * only costs bytes at the time — {@link RedisCheckpointStore} keeps failing transfers going without a
 * checkpoint — but it silently turns every large transfer non-resumable, so it must be visible rather
 * than discovered only when a large upload restarts from byte zero.
 */
@Component("checkpointHealthIndicator")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class CheckpointHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redisTemplate;

    @Override
    public Health health() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return Health.up().build();
        } catch (DataAccessException e) {
            return Health.down(e).build();
        }
    }
}
