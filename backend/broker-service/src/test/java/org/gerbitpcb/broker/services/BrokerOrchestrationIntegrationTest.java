package org.gerbitpcb.broker.services;

import org.gerbitpcb.broker.domain.Transaction;
import org.gerbitpcb.broker.domain.TransactionStatus;
import org.gerbitpcb.broker.dto.CreateTransactionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@ActiveProfiles("test")
class BrokerOrchestrationIntegrationTest {

    @Autowired
    private BrokerOrchestrationService brokerService;

    @Autowired
    private RestTemplate restTemplate;

    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    private CreateTransactionRequest createRequest() {
        return new CreateTransactionRequest("Customer", List.of(
                new CreateTransactionRequest.Item("TI", "TI-SKU", 2, new BigDecimal("1.50")),
                new CreateTransactionRequest.Item("Murata", "MU-SKU", 3, new BigDecimal("2.25"))
        ));
    }

    @Test
    void testBroker_SupplierTimeout_TriggersFailure() {
        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8081/api/transaction/reserve")) // TI
                .andRespond(withSuccess("{\"reservationId\":\"123\"}", MediaType.APPLICATION_JSON));

        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8082/api/transaction/reserve")) // Murata
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        // Trigger rollback on TI because of Murata failure
        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8081/api/transaction/rollback"))
                .andRespond(withSuccess());

        Transaction transaction = brokerService.createTransaction(createRequest());

        assertEquals(TransactionStatus.FAILED, transaction.getStatus());
        assertTrue(transaction.getAuditTrail().stream()
                .anyMatch(a -> "FAILED".equals(a.getStatus()) && a.getFailureReason() != null));
                
        mockServer.verify();
    }
    
    @Test
    void testBroker_MalformedJsonResponse_TriggersFailure() {
        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8081/api/transaction/reserve"))
                .andRespond(withSuccess("<html>Database Error</html>", MediaType.TEXT_HTML));

        // Let rollback fail or succeed depending on our test setup. For this test we just want to verify it triggers.
        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8081/api/transaction/rollback"))
                .andRespond(withSuccess());

        Transaction transaction = brokerService.createTransaction(createRequest());

        assertEquals(TransactionStatus.FAILED, transaction.getStatus());
        assertTrue(transaction.getAuditTrail().stream()
                .anyMatch(a -> a.getFailureReason().contains("MALFORMED_RESPONSE") || a.getFailureReason().contains("parse")));
                
        mockServer.verify();
    }
}
