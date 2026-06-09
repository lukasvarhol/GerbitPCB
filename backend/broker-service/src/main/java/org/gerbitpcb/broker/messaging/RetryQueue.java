package org.gerbitpcb.broker.messaging;

import java.time.Duration;
import java.util.UUID;

/**
 * The broker enqueues the {@code transactionId} of an order that could not be
 * completed synchronously (a supplier was unreachable); a background consumer
 * pulls it and re-drives completion. {@link #scheduleEnqueue} delays redelivery
 * for exponential backoff.
 * Hiding the queue behind this interface lets tests use an in-memory fake (with a
 * virtual clock) instead of hitting Azure Service Bus — see {@code InMemoryRetryQueue}.
 */
public interface RetryQueue {

    void enqueue(UUID transactionId);

    void scheduleEnqueue(UUID transactionId, Duration delay);
}
