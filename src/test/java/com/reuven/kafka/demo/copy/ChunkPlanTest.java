package com.reuven.kafka.demo.copy;

import com.reuven.kafka.demo.copy.delivery.ChunkPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkPlanTest {

    private static final long DEFAULT_BASE_PART_SIZE = DataSize.ofMegabytes(16).toBytes();

    @Test
    @DisplayName("just above the chunking threshold: base part size governs, several parts")
    void justAboveThreshold() {
        long payload = DataSize.ofMegabytes(100).toBytes() + 1;
        ChunkPlan plan = ChunkPlan.forPayload(payload, DEFAULT_BASE_PART_SIZE);

        assertThat(plan.partSize()).isEqualTo(DEFAULT_BASE_PART_SIZE);
        assertThat(plan.partCount()).isGreaterThan(1);
        assertThat((long) (plan.partCount() - 1) * plan.partSize()).isLessThan(payload);
    }

    @Test
    @DisplayName("part-count ceiling at 5 TiB: size-driven part size, well under 10,000 parts")
    void partCountCeilingAtFiveTebibytes() {
        long payload = DataSize.ofTerabytes(5).toBytes();
        ChunkPlan plan = ChunkPlan.forPayload(payload, DEFAULT_BASE_PART_SIZE);

        assertThat(plan.partSize()).isBetween(DataSize.ofMegabytes(500).toBytes(), DataSize.ofMegabytes(600).toBytes());
        assertThat(plan.partCount()).isLessThanOrEqualTo(ChunkPlan.MAX_PARTS);
    }

    @Test
    @DisplayName("minimum part size floor: a tiny base part size is still raised to 5 MiB")
    void minimumPartSizeFloor() {
        long payload = DataSize.ofMegabytes(20).toBytes();
        long tinyBase = DataSize.ofMegabytes(1).toBytes();
        ChunkPlan plan = ChunkPlan.forPayload(payload, tinyBase);

        assertThat(plan.partSize()).isEqualTo(ChunkPlan.MIN_PART_SIZE);
    }

    @Test
    @DisplayName("the final part is permitted to fall below the minimum part size")
    void finalPartBelowMinimumIsPermitted() {
        long partSize = ChunkPlan.MIN_PART_SIZE;
        long payload = partSize * 2 + 1;
        ChunkPlan plan = ChunkPlan.forPayload(payload, partSize);

        assertThat(plan.partCount()).isEqualTo(3);
        long lastPartSize = payload - ((long) (plan.partCount() - 1) * plan.partSize());
        assertThat(lastPartSize).isEqualTo(1);
    }

    @Test
    @DisplayName("a base part size above the S3 part maximum is rejected, not silently clamped")
    void aboveMaximumPartSizeIsRejected() {
        long payload = DataSize.ofGigabytes(10).toBytes();
        long tooLargeBase = DataSize.ofGigabytes(6).toBytes();

        assertThatThrownBy(() -> ChunkPlan.forPayload(payload, tooLargeBase))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5")
                .hasMessageContaining("maximum");
    }

    @Test
    @DisplayName("part size never exceeds the S3 maximum even for a payload beyond the 5 TiB assumption")
    void extremePayloadBeyondAssumptionIsRejectedRatherThanCorrupted() {
        long payload = DataSize.ofTerabytes(60).toBytes();

        assertThatThrownBy(() -> ChunkPlan.forPayload(payload, DEFAULT_BASE_PART_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
