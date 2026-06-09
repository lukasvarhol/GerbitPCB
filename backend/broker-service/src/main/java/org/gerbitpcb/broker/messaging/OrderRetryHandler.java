package org.gerbitpcb.broker.messaging;

import org.gerbitpcb.broker.domain.Transaction;
import org.gerbitpcb.broker.domain.TransactionStatus;
import org.gerbitpcb.broker.services.BrokerOrchestrationService;
import org.gerbitpcb.broker.services.BrokerOrchestrationService.CompletionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Drives a queued transaction toward completion. Invoked by the Service Bus consumer
 * (one message = one transaction id) and shared so the logic is unit-testable without a queue.
 * Per attempt: if already terminal, ignore (duplicate delivery); if past the 15-minute
 * deadline, global-rollback (abort); otherwise re-drive {@code attemptCompletion} and, while
 * still failing transiently, re-schedule with backoff until the deadline.
 */
@Component
public class OrderRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderRetryHandler.class);

    private final BrokerOrchestrationService broker;
    private final RetryQueue retryQueue;
    private final List<Duration> backoff;
    private Clock clock = Clock.systemUTC();

    public OrderRetryHandler(BrokerOrchestrationService broker,
                             RetryQueue retryQueue,
                             @Value("${app.retry.backoff:PT30S,PT1M,PT2M,PT5M}") List<String> backoff) {
        this.broker = broker;
        this.retryQueue = retryQueue;
        this.backoff = new ArrayList<>();
        for (String b : backoff) {
            this.backoff.add(Duration.parse(b.trim()));
        }
    }

    @Autowired(required = false)
    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    public void handle(UUID transactionId) {
        Transaction txn = broker.getTransaction(transactionId);
        if (isTerminal(txn.getStatus())) {
            return; // already settled (duplicate delivery)
        }
        if (txn.getDeadlineAt() != null && Instant.now(clock).isAfter(txn.getDeadlineAt())) {
            log.info("Retry deadline exceeded for {} -> global rollback", transactionId);
            broker.abort(transactionId);
            return;
        }
        CompletionResult result = broker.attemptCompletion(transactionId);
        switch (result) {
            case COMPLETED -> log.info("Async completion succeeded for {}", transactionId);
            case TERMINAL_FAILED -> broker.abort(transactionId);
            case RETRYABLE -> {
                int attempt = broker.getTransaction(transactionId).getAttemptCount();
                Duration delay = nextBackoff(attempt);
                log.info("Async attempt {} for {} still failing; re-scheduling in {}", attempt, transactionId, delay);
                retryQueue.scheduleEnqueue(transactionId, delay);
            }
        }
    }

    // expired message landed in the dead-letter queue => terminal global rollback
    public void handleDeadLetter(UUID transactionId) {
        log.warn("Dead-lettered retry message for {} -> global rollback", transactionId);
        broker.abort(transactionId);
    }

    private Duration nextBackoff(int attempt) {
        if (backoff.isEmpty()) {
            return Duration.ofMinutes(1);
        }
        int idx = Math.min(Math.max(attempt - 1, 0), backoff.size() - 1);
        return backoff.get(idx);
    }

    private boolean isTerminal(TransactionStatus status) {
        return status == TransactionStatus.COMMITTED
                || status == TransactionStatus.ROLLED_BACK
                || status == TransactionStatus.FAILED;
    }
}
