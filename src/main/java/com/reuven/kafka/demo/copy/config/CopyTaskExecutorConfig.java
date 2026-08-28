package com.reuven.kafka.demo.copy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Spring-managed thread pools backing the staged strategy's background loops — replaces manual
 * {@code new Thread(...)} construction (unsafe: no lifecycle management, no pool bounds) with
 * container-managed beans whose start/stop is driven by the Spring lifecycle.
 */
@Configuration
public class CopyTaskExecutorConfig {

    /**
     * Backs the fixed-interval {@code SmartLifecycle} pollers (release signal, abandoned-upload
     * reaper, claim reaper, backlog governor) via {@code scheduleWithFixedDelay}. Unlike a plain
     * executor, a scheduled task releases its pool thread between runs instead of parking on
     * {@code Thread.sleep()}, so a handful of short, infrequent scans can share a small pool
     * instead of each pinning a thread for the app's whole lifetime.
     */
    @Bean
    public ThreadPoolTaskScheduler copyPollerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("copy-poller-");
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Backs {@code DeliveryWorker}'s transfer loops on <b>virtual</b> threads. Every worker spends
     * nearly all its life blocked — on provider socket reads, on S3 part uploads, on the idle
     * backoff between claims — which is exactly the workload virtual threads exist for: a blocked
     * virtual thread costs no carrier thread and no pre-reserved ~1MB stack, unlike the platform
     * thread it replaces. Java 25 makes this unconditionally safe, since JEP 491 (JDK 24) removed
     * carrier pinning on {@code synchronized}, the last hazard for blocking libraries like the AWS
     * SDK's sync client.
     *
     * <p>{@code concurrencyLimit} preserves the previous pool's semantics — at most
     * {@code copy.delivery.worker-concurrency} transfers in flight. Note it throttles by blocking
     * the <i>submitting</i> thread, which is harmless here because {@code DeliveryWorker#start}
     * submits exactly that many tasks; anything submitted beyond the limit would stall its caller.
     */
    @Bean
    public AsyncTaskExecutor copyDeliveryTaskExecutor(CopyProperties properties) {
        int concurrency = Math.max(1, properties.delivery().workerConcurrency());
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("copy-delivery-worker-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(concurrency);
        return executor;
    }
}
