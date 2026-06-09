package org.gerbitpcb.broker.services;

import org.gerbitpcb.broker.domain.Transaction;
import org.gerbitpcb.broker.domain.TransactionItem;
import org.gerbitpcb.broker.domain.TransactionStatus;
import org.gerbitpcb.broker.messaging.InMemoryRetryQueue;
import org.gerbitpcb.broker.messaging.OrderRetryHandler;
import org.gerbitpcb.broker.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * End-to-end async retry loop (Phase 4), driven through the real {@link OrderRetryHandler} +
 * {@link BrokerOrchestrationService} with the supplier mocked via {@link MockRestServiceServer}
 * and the in-memory queue standing in for Service Bus.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "suppliers.endpoints.TI=http://localhost:8081",
        "suppliers.endpoints.Murata=http://localhost:8082"
})
class BrokerAsyncRetryIntegrationTest {

    @Autowired
    private BrokerOrchestrationService brokerService;

    @Autowired
    private OrderRetryHandler retryHandler;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private InMemoryRetryQueue retryQueue;

    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.createServer(restTemplate);
        transactionRepository.deleteAll();
        retryQueue.clear();
    }

    @Test
    void retry_supplierBack_commitsTheOrder() {
        UUID id = persist(TransactionStatus.RETRYING, Instant.now().plus(Duration.ofMinutes(10)), UUID.randomUUID());

        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8081/api/transaction/commit"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        retryHandler.handle(id);

        assertEquals(TransactionStatus.COMMITTED, transactionRepository.findById(id).orElseThrow().getStatus());
        mockServer.verify();
    }

    @Test
    void retry_deadlineExceeded_globallyRollsBack() {
        UUID id = persist(TransactionStatus.RETRYING, Instant.now().minus(Duration.ofMinutes(1)), UUID.randomUUID());

        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8081/api/transaction/rollback"))
                .andRespond(withSuccess());

        retryHandler.handle(id);

        assertEquals(TransactionStatus.FAILED, transactionRepository.findById(id).orElseThrow().getStatus());
        mockServer.verify();
    }

    @Test
    void retry_stillUnreachable_reschedulesWithinDeadline() {
        UUID id = persist(TransactionStatus.RETRYING, Instant.now().plus(Duration.ofMinutes(10)), null);

        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8081/api/transaction/reserve"))
                .andRespond(request -> {
                    throw new SocketTimeoutException("supplier still down");
                });

        retryHandler.handle(id);

        assertEquals(TransactionStatus.RETRYING, transactionRepository.findById(id).orElseThrow().getStatus());
        assertFalse(retryQueue.getScheduled().isEmpty(), "a transient failure should re-schedule a retry");
        mockServer.verify();
    }

    private UUID persist(TransactionStatus status, Instant deadline, UUID reservationId) {
        Transaction txn = new Transaction();
        txn.setCustomerName("async");
        txn.setStatus(status);
        txn.setDeadlineAt(deadline);

        TransactionItem item = new TransactionItem();
        item.setSupplier("TI");
        item.setSku("TI-SKU");
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("1.00"));
        item.setReservationId(reservationId);

        List<TransactionItem> items = new ArrayList<>();
        items.add(item);
        txn.setItems(items);

        return transactionRepository.save(txn).getId();
    }
}
