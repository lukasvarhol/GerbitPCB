package org.gerbitpcb.broker.messaging;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link RetryQueue} for tests and local dev — records what was enqueued
 * instead of talking to Azure Service Bus, so test suites stay deterministic and
 * can fast-forward backoff/deadline timing with a virtual clock.
 * Not registered as a Spring bean; wire it explicitly in tests.
 */
public class InMemoryRetryQueue implements RetryQueue {

    public record Scheduled(UUID transactionId, Duration delay) {}

    private final List<UUID> enqueued = new CopyOnWriteArrayList<>();
    private final List<Scheduled> scheduled = new CopyOnWriteArrayList<>();

    @Override
    public void enqueue(UUID transactionId) {
        enqueued.add(transactionId);
    }

    @Override
    public void scheduleEnqueue(UUID transactionId, Duration delay) {
        scheduled.add(new Scheduled(transactionId, delay));
    }

    public List<UUID> getEnqueued() {
        return enqueued;
    }

    public List<Scheduled> getScheduled() {
        return scheduled;
    }

    public void clear() {
        enqueued.clear();
        scheduled.clear();
    }
}
