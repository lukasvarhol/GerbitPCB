package org.gerbitpcb.broker.services;

import org.gerbitpcb.broker.domain.Transaction;
import org.gerbitpcb.broker.domain.TransactionItem;
import org.gerbitpcb.broker.domain.TransactionStatus;
import org.gerbitpcb.broker.messaging.InMemoryRetryQueue;
import org.gerbitpcb.broker.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-4 demoted sweeper: it no longer drives commits/rollbacks itself (that would race the
 * queue) — it re-enqueues stuck transactions for the async consumer to recover.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "suppliers.endpoints.TI=http://localhost:8081",
        "suppliers.endpoints.Murata=http://localhost:8082"
})
class BrokerRecoverySweepIntegrationTest {

    @Autowired
    private BrokerOrchestrationService brokerService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private InMemoryRetryQueue retryQueue;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        retryQueue.clear();
    }

    @Test
    void sweep_reEnqueuesStuckTransactions() {
        UUID pending = buildStuck(TransactionStatus.PENDING).getId();
        UUID prepared = buildStuck(TransactionStatus.PREPARED).getId();
        UUID partial = buildStuck(TransactionStatus.PARTIALLY_COMMITTED).getId();

        brokerService.sweepAndResumeStuckTransactions();

        assertTrue(retryQueue.getEnqueued().containsAll(List.of(pending, prepared, partial)),
                "all stuck transactions should be re-enqueued for async recovery");
    }

    @Test
    void sweep_ignoresRecentTransactions() {
        Transaction recent = new Transaction();
        recent.setCustomerName("recent");
        recent.setStatus(TransactionStatus.PENDING);
        // startedAt defaults to now -> within the 1-minute buffer
        transactionRepository.save(recent);

        brokerService.sweepAndResumeStuckTransactions();

        assertTrue(retryQueue.getEnqueued().isEmpty(),
                "recent transactions are within the buffer and must not be swept");
    }

    private Transaction buildStuck(TransactionStatus status) {
        Transaction txn = new Transaction();
        txn.setCustomerName("RecoveryTest");
        txn.setStatus(status);

        TransactionItem item = new TransactionItem();
        item.setSupplier("TI");
        item.setSku("TI-SKU");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("1.50"));
        item.setReservationId(UUID.randomUUID());

        List<TransactionItem> items = new ArrayList<>();
        items.add(item);
        txn.setItems(items);

        ReflectionTestUtils.setField(txn, "startedAt", Instant.now().minus(Duration.ofMinutes(2)));
        return transactionRepository.save(txn);
    }
}
