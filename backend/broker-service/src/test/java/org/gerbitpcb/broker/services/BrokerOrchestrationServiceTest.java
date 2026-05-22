package org.gerbitpcb.broker.services;

import org.gerbitpcb.broker.config.SupplierConfiguration;
import org.gerbitpcb.broker.domain.Transaction;
import org.gerbitpcb.broker.domain.TransactionStatus;
import org.gerbitpcb.broker.dto.CreateTransactionRequest;
import org.gerbitpcb.broker.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrokerOrchestrationServiceTest {

    private static final String TI = "TI";
    private static final String MURATA = "Murata";
    private static final String TI_BASE_URL = "http://ti";
    private static final String MURATA_BASE_URL = "http://murata";

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private SupplierConfiguration supplierConfiguration;

    @InjectMocks
    private BrokerOrchestrationService brokerOrchestrationService;

    private CreateTransactionRequest request;

    @BeforeEach
    void setUp() {
        request = new CreateTransactionRequest(
                "Customer",
                List.of(
                        new CreateTransactionRequest.Item(TI, "TI-SKU", 2, new BigDecimal("1.50")),
                        new CreateTransactionRequest.Item(MURATA, "MU-SKU", 3, new BigDecimal("2.25"))
                )
        );

        when(supplierConfiguration.getSupplierUrl(TI)).thenReturn(TI_BASE_URL);
        when(supplierConfiguration.getSupplierUrl(MURATA)).thenReturn(MURATA_BASE_URL);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void testOrchestration_HappyPath_AllReservesSucceed_ThenCommit() {
        UUID tiReservation = UUID.randomUUID();
        UUID muReservation = UUID.randomUUID();

        when(restTemplate.postForEntity(
                eq(TI_BASE_URL + "/api/transaction/reserve"),
                any(Map.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("reservationId", tiReservation.toString())));

        when(restTemplate.postForEntity(
                eq(MURATA_BASE_URL + "/api/transaction/reserve"),
                any(Map.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("reservationId", muReservation.toString())));

        Transaction prepared = brokerOrchestrationService.createTransaction(request);
        assertEquals(TransactionStatus.PREPARED, prepared.getStatus());

        when(transactionRepository.findById(prepared.getId())).thenReturn(Optional.of(prepared));

        brokerOrchestrationService.commitTransaction(prepared.getId());

        verify(restTemplate, times(1)).postForEntity(
                eq(TI_BASE_URL + "/api/transaction/commit"),
                any(Map.class),
                eq(Void.class));
        verify(restTemplate, times(1)).postForEntity(
                eq(MURATA_BASE_URL + "/api/transaction/commit"),
                any(Map.class),
                eq(Void.class));
    }

    @Test
    void testOrchestration_PartialFailure_OneReserveFails_ThenRollback() {
        UUID tiReservation = UUID.randomUUID();

        when(restTemplate.postForEntity(
                eq(TI_BASE_URL + "/api/transaction/reserve"),
                any(Map.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("reservationId", tiReservation.toString())));

        when(restTemplate.postForEntity(
                eq(MURATA_BASE_URL + "/api/transaction/reserve"),
                any(Map.class),
                eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null, null, null));

        when(restTemplate.postForEntity(
                eq(TI_BASE_URL + "/api/transaction/rollback"),
                any(Map.class),
                eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());

        Transaction failed = brokerOrchestrationService.createTransaction(request);

        assertEquals(TransactionStatus.FAILED, failed.getStatus());
        verify(restTemplate, times(1)).postForEntity(
                eq(TI_BASE_URL + "/api/transaction/rollback"),
                any(Map.class),
                eq(Void.class));
    }

    @Test
    void testOrchestration_SupplierDown_ConnectionRefused_ThenRollback() {
        UUID tiReservation = UUID.randomUUID();

        when(restTemplate.postForEntity(
                eq(TI_BASE_URL + "/api/transaction/reserve"),
                any(Map.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("reservationId", tiReservation.toString())));

        when(restTemplate.postForEntity(
                eq(MURATA_BASE_URL + "/api/transaction/reserve"),
                any(Map.class),
                eq(Map.class)))
                .thenThrow(new RestClientException("Connection refused"));

        when(restTemplate.postForEntity(
                eq(TI_BASE_URL + "/api/transaction/rollback"),
                any(Map.class),
                eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());

        Transaction failed = brokerOrchestrationService.createTransaction(request);

        assertEquals(TransactionStatus.FAILED, failed.getStatus());
        assertNotNull(failed.getAuditTrail());
        verify(restTemplate, times(1)).postForEntity(
                eq(TI_BASE_URL + "/api/transaction/rollback"),
                any(Map.class),
                eq(Void.class));
    }

    @Test
    void testOrchestration_MalformedReserveResponse_ThenRollback() {
        UUID tiReservation = UUID.randomUUID();

        when(restTemplate.postForEntity(
                eq(TI_BASE_URL + "/api/transaction/reserve"),
                any(Map.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("reservationId", tiReservation.toString())));

        when(restTemplate.postForEntity(
                eq(MURATA_BASE_URL + "/api/transaction/reserve"),
                any(Map.class),
                eq(Map.class)))
                .thenThrow(new HttpMessageConversionException("JSON parse error"));

        when(restTemplate.postForEntity(
                eq(TI_BASE_URL + "/api/transaction/rollback"),
                any(Map.class),
                eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());

        Transaction failed = brokerOrchestrationService.createTransaction(request);

        assertEquals(TransactionStatus.FAILED, failed.getStatus());
        assertTrue(failed.getAuditTrail().stream()
                .anyMatch(audit -> "PREPARE".equals(audit.getPhase())
                        && MURATA.equals(audit.getSupplier())
                        && "FAILED".equals(audit.getStatus())
                        && "MALFORMED_RESPONSE: JSON parse error".equals(audit.getFailureReason())));
        verify(restTemplate, times(1)).postForEntity(
                eq(TI_BASE_URL + "/api/transaction/rollback"),
                any(Map.class),
                eq(Void.class));
    }
}
