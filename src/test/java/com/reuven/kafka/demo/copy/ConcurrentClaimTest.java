package com.reuven.kafka.demo.copy;

import com.reuven.kafka.demo.copy.staging.StagedItem;
import com.reuven.kafka.demo.copy.staging.StagedItemRepository;
import com.reuven.kafka.demo.copy.support.CopyIntegrationTestBase;
import com.reuven.kafka.demo.copy.support.CopyTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * data-model.md invariant I5: no two workers transfer the same item concurrently. Covers the
 * "two workers pick up the same staged item" edge case and FR-017's claim-reaper recovery.
 */
class ConcurrentClaimTest extends CopyIntegrationTestBase {

    /**
     * The real {@code DeliveryWorker} background threads run under {@code @SpringBootTest} too and
     * would otherwise race this test's own manual claim attempts for the same row. Zero
     * worker-concurrency (a legitimate production value — see CopyProperties) turns them off so this
     * test drives claiming entirely itself.
     */
    @DynamicPropertySource
    static void noBackgroundWorkers(DynamicPropertyRegistry registry) {
        registry.add("copy.delivery.worker-concurrency", () -> 0);
    }

    @Autowired
    private StagedItemRepository repository;

    @Autowired
    private Clock clock;

    @Test
    void onlyOneOfManyWorkersClaimsTheSameItem() throws Exception {
        StagedItem item = repository.save(CopyTestFixtures.stagedItemBuilder("concurrent-claim-" + System.nanoTime()).build());

        int workerCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        try {
            List<Callable<Optional<StagedItem>>> tasks = IntStream.range(0, workerCount)
                    .<Callable<Optional<StagedItem>>>mapToObj(i -> () -> claim("worker-" + i))
                    .toList();

            List<Future<Optional<StagedItem>>> futures = executor.invokeAll(tasks);
            List<Optional<StagedItem>> results = new java.util.ArrayList<>();
            for (Future<Optional<StagedItem>> future : futures) {
                results.add(future.get());
            }

            long successfulClaims = results.stream().filter(Optional::isPresent).count();
            assertThat(successfulClaims)
                    .as("exactly one worker should have claimed the single staged item")
                    .isEqualTo(1);

            Set<String> owners = results.stream()
                    .flatMap(Optional::stream)
                    .map(StagedItem::getClaimOwner)
                    .collect(Collectors.toSet());
            assertThat(owners).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aHeartbeatingWorkerIsNeverReclaimedBeneathItself() {
        StagedItem item = repository.save(CopyTestFixtures.stagedItemBuilder("heartbeat-claim-" + System.nanoTime()).build());
        Instant now = Instant.now(clock);

        Optional<StagedItem> claimed = repository.claimNext("steady-worker", now.plusSeconds(300), now);
        assertThat(claimed).isPresent();

        // Heartbeat: extend the claim well past "now" before any reaper scan would consider it stale.
        StagedItem claimedItem = claimed.get();
        claimedItem.setClaimExpiresAt(now.plusSeconds(300));
        repository.save(claimedItem);

        List<StagedItem> stale = repository.findStaleClaims(now);
        assertThat(stale).noneMatch(i -> i.getId().equals(item.getId()));
    }

    private Optional<StagedItem> claim(String workerId) {
        Instant now = Instant.now(clock);
        return repository.claimNext(workerId, now.plusSeconds(300), now);
    }
}
