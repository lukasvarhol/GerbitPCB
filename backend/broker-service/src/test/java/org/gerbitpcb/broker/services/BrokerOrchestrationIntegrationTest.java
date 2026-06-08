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
import org.springframework.test.context.TestPropertySource;
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
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "suppliers.endpoints.TI=http://localhost:8081",
        "suppliers.endpoints.Murata=http://localhost:8082"
})
class BrokerOrchestrationIntegrationTest {

    @Autowired
    private BrokerOrchestrationService brokerService;

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
    }

    private CreateTransactionRequest createRequest() {
        return new CreateTransactionRequest("Customer", List.of(
                new CreateTransactionRequest.Item("TI", "TI-SKU", 2, new BigDecimal("1.50")),
                new CreateTransactionRequest.Item("Murata", "MU-SKU", 3, new BigDecimal("2.25"))
        ));
    }

    @Test
    void testBroker_SupplierTimeout_TriggersFailure() {
        String tiReservationId = "3bde3f1a-62e1-4a1b-8f5b-94d4db4a6a2f";
        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8081/api/transaction/reserve")) // TI
                .andRespond(withSuccess("{\"reservationId\":\"" + tiReservationId + "\"}", MediaType.APPLICATION_JSON));

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

        Transaction transaction = brokerService.createTransaction(createRequest());

        assertEquals(TransactionStatus.FAILED, transaction.getStatus());
        assertTrue(transaction.getAuditTrail().stream()
                .anyMatch(a -> a.getFailureReason() != null && a.getFailureReason().contains("MALFORMED_RESPONSE")));
                
        mockServer.verify();
    }
}
