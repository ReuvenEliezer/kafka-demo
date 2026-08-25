package com.reuven.kafka.demo.copy.staging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Durably stages a consumed batch in one transaction, all-or-nothing (FR-009). Uses a plain JDBC
 * batch insert with {@code ON CONFLICT (recording_file_id) DO NOTHING} rather than
 * {@code JpaRepository#saveAll} — the latter throws on the first duplicate instead of skipping it,
 * which would defeat at-least-once redelivery idempotency.
 *
 * <p>Callers supply only the business fields ({@code recordingFileId}, {@code sessionId},
 * {@code providerAccountId}, {@code providerEventId}, {@code destinationBucket},
 * {@code destinationKey}, {@code declaredSizeBytes}, {@code contentType}); the staging-specific
 * defaults (initial state, counters, timestamps) are this service's concern.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class StagingService {

    private static final String INSERT_SQL = """
            INSERT INTO staged_item (
                recording_file_id, session_id, provider_account_id, provider_event_id,
                destination_bucket, destination_key, declared_size_bytes, content_type,
                delivery_state, attempt_count, next_attempt_at, release_state,
                release_attempt_count, created_at, updated_at
            ) VALUES (
                ?, ?, ?, ?,
                ?, ?, ?, ?,
                'AWAITING_DELIVERY', 0, ?, 'NOT_APPLICABLE',
                0, ?, ?
            )
            ON CONFLICT (recording_file_id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Transactional
    public void stage(List<StagedItem> items) {
        if (items.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        Timestamp nowTimestamp = Timestamp.from(now);

        int[] results = jdbcTemplate.batchUpdate(INSERT_SQL, items, items.size(), (ps, item) -> {
            ps.setString(1, item.getRecordingFileId());
            ps.setString(2, item.getSessionId());
            ps.setString(3, item.getProviderAccountId());
            ps.setString(4, item.getProviderEventId());
            ps.setString(5, item.getDestinationBucket());
            ps.setString(6, item.getDestinationKey());
            if (item.getDeclaredSizeBytes() != null) {
                ps.setLong(7, item.getDeclaredSizeBytes());
            } else {
                ps.setNull(7, Types.BIGINT);
            }
            ps.setString(8, item.getContentType());
            ps.setTimestamp(9, nowTimestamp);
            ps.setTimestamp(10, nowTimestamp);
            ps.setTimestamp(11, nowTimestamp);
        })[0];

        long inserted = java.util.Arrays.stream(results).filter(r -> r > 0).count();
        log.debug("Staged batch of {} messages: {} new rows, {} duplicates skipped",
                items.size(), inserted, items.size() - inserted);
    }
}
