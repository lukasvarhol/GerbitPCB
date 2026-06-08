package org.gerbitpcb.broker.services;

import org.gerbitpcb.broker.domain.Transaction;
import org.gerbitpcb.broker.domain.TransactionItem;
import org.gerbitpcb.broker.domain.TransactionStatus;
import org.gerbitpcb.broker.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "suppliers.endpoints.TI=http://localhost:8081",
        "suppliers.endpoints.Murata=http://localhost:8082"
})
class BrokerRecoverySweepIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BrokerOrchestrationService brokerService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private RestTemplate restTemplate;

    @MockBean
    private OAuth2AuthorizedClientManager authorizedClientManager;

    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        // 1. Create a fake Auth0 Token
        OAuth2AccessToken fakeToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, 
                "mock-test-token", 
                Instant.now(), 
                Instant.now().plusSeconds(3600)
        );

        // 2. Wrap it in a dummy Client Registration
        ClientRegistration clientReg = ClientRegistration.withRegistrationId("broker")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientId("test-client")
                .tokenUri("http://test-uri")
                .build();

        OAuth2AuthorizedClient authClient = new OAuth2AuthorizedClient(clientReg, "broker-service", fakeToken);

        // 3. Tell the Mocked Manager to return the fake token whenever the interceptor asks
        when(authorizedClientManager.authorize(any())).thenReturn(authClient);

        // 4. Initialize the Mock Server
        mockServer = MockRestServiceServer.createServer(restTemplate);
        
        transactionRepository.deleteAll();
    }

    @Test
    void sweepPendingTransaction_TriggersRollback() {
        Transaction txn = buildTransaction(TransactionStatus.PENDING,
                new TransactionItemSpec("TI", "TI-SKU", 2, new BigDecimal("1.50"), UUID.randomUUID()));

        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8081/api/transaction/rollback"))
                .andRespond(withSuccess());

        brokerService.sweepAndResumeStuckTransactions();

        Transaction refreshed = transactionRepository.findById(txn.getId()).orElseThrow();
        assertEquals(TransactionStatus.ROLLED_BACK, refreshed.getStatus());
        mockServer.verify();
    }

    @Test
    void sweepPreparedTransaction_TriggersCommit() {
        Transaction txn = buildTransaction(TransactionStatus.PREPARED,
                new TransactionItemSpec("TI", "TI-SKU", 2, new BigDecimal("1.50"), UUID.randomUUID()),
                new TransactionItemSpec("Murata", "MU-SKU", 3, new BigDecimal("2.25"), UUID.randomUUID()));

        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8081/api/transaction/commit"))
                .andRespond(withSuccess());
        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8082/api/transaction/commit"))
                .andRespond(withSuccess());

        brokerService.sweepAndResumeStuckTransactions();

        Transaction refreshed = transactionRepository.findById(txn.getId()).orElseThrow();
        assertEquals(TransactionStatus.COMMITTED, refreshed.getStatus());
        mockServer.verify();
    }

    @Test
    void sweepPartiallyCommittedTransaction_TriggersCommit() {
        // Arrange: Transaction is PARTIALLY_COMMITTED, meaning Phase 2 broke halfway.
        Transaction txn = buildTransaction(TransactionStatus.PARTIALLY_COMMITTED,
                new TransactionItemSpec("TI", "TI-SKU", 2, new BigDecimal("1.50"), UUID.randomUUID()),
                new TransactionItemSpec("Murata", "MU-SKU", 3, new BigDecimal("2.25"), UUID.randomUUID()));

        // The Broker will re-fire the commit to BOTH suppliers.
        // We expect both to respond with Success (200 OK) due to Supplier idempotency.
        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8081/api/transaction/commit"))
                .andRespond(withSuccess());
        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8082/api/transaction/commit"))
                .andRespond(withSuccess());

        // Act: Run the background sweep job
        brokerService.sweepAndResumeStuckTransactions();

        // Assert: The split-brain is healed, and the transaction is fully COMMITTED.
        Transaction refreshed = transactionRepository.findById(txn.getId()).orElseThrow();
        assertEquals(TransactionStatus.COMMITTED, refreshed.getStatus());
        mockServer.verify();
    }

    private Transaction buildTransaction(TransactionStatus status, TransactionItemSpec... items) {
        Transaction txn = new Transaction();
        txn.setCustomerName("RecoveryTest");
        txn.setStatus(status);

        List<TransactionItem> transactionItems = new ArrayList<>(items.length);
        for (TransactionItemSpec spec : items) {
            TransactionItem item = new TransactionItem();
            item.setSupplier(spec.supplier());
            item.setSku(spec.sku());
            item.setQuantity(spec.quantity());
            item.setUnitPrice(spec.unitPrice());
            item.setReservationId(spec.reservationId());
            item.setTransaction(txn);
            transactionItems.add(item);
        }

        txn.setItems(transactionItems);

        Transaction savedTxn = transactionRepository.save(txn);

        jdbcTemplate.update(
                "UPDATE transactions SET started_at = ? WHERE id = ?",
                Instant.now().minus(Duration.ofMinutes(2)),
                savedTxn.getId()
        );

        // Return a freshly loaded instance from the DB
        return transactionRepository.findById(savedTxn.getId()).orElseThrow();
    }

    private record TransactionItemSpec(String supplier, String sku, int quantity, BigDecimal unitPrice, UUID reservationId) {
    }
}
