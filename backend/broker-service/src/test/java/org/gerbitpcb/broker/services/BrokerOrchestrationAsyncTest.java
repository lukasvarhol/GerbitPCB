package org.gerbitpcb.broker.services;

import org.gerbitpcb.broker.config.SupplierConfiguration;
import org.gerbitpcb.broker.domain.Transaction;
import org.gerbitpcb.broker.domain.TransactionItem;
import org.gerbitpcb.broker.domain.TransactionStatus;
import org.gerbitpcb.broker.repository.TransactionRepository;
import org.gerbitpcb.broker.services.BrokerOrchestrationService.CompletionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Phase-4 async-retry primitives ({@code attemptCompletion} / {@code abort}).
 * These exercise the failure classifier: transport failures are retryable, business
 * rejections are terminal.
 */
@ExtendWith(MockitoExtension.class)
class BrokerOrchestrationAsyncTest {

    private static final String TI = "TI";
    private static final String MURATA = "Murata";
    private static final String TI_URL = "http://ti";
    private static final String MU_URL = "http://murata";

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private SupplierConfiguration supplierConfiguration;

    @InjectMocks
    private BrokerOrchestrationService service;

    private Transaction txn;

    @BeforeEach
    void setUp() {
        txn = new Transaction();
        txn.setCustomerName("Async");
        txn.setStatus(TransactionStatus.PENDING);
        List<TransactionItem> items = new ArrayList<>();
        items.add(item(TI, "TI-SKU", 2));
        items.add(item(MURATA, "MU-SKU", 3));
        txn.setItems(items);

        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private TransactionItem item(String supplier, String sku, int qty) {
        TransactionItem it = new TransactionItem();
        it.setSupplier(supplier);
        it.setSku(sku);
        it.setQuantity(qty);
        it.setUnitPrice(new BigDecimal("1.00"));
        return it;
    }

    @Test
    void attemptCompletion_allReserveAndCommit_returnsCompleted() {
        when(supplierConfiguration.getSupplierUrl(TI)).thenReturn(TI_URL);
        when(supplierConfiguration.getSupplierUrl(MURATA)).thenReturn(MU_URL);
        when(restTemplate.postForEntity(eq(TI_URL + "/api/transaction/reserve"), any(Map.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("reservationId", UUID.randomUUID().toString())));
        when(restTemplate.postForEntity(eq(MU_URL + "/api/transaction/reserve"), any(Map.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("reservationId", UUID.randomUUID().toString())));
        when(restTemplate.postForEntity(any(String.class), any(Map.class), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());

        CompletionResult result = service.attemptCompletion(txn.getId());

        assertEquals(CompletionResult.COMPLETED, result);
        assertEquals(TransactionStatus.COMMITTED, txn.getStatus());
        verify(restTemplate).postForEntity(eq(TI_URL + "/api/transaction/commit"), any(Map.class), eq(Void.class));
        verify(restTemplate).postForEntity(eq(MU_URL + "/api/transaction/commit"), any(Map.class), eq(Void.class));
    }

    @Test
    void attemptCompletion_supplierUnreachableDuringReserve_returnsRetryable_andKeepsEarlierReservation() {
        when(supplierConfiguration.getSupplierUrl(TI)).thenReturn(TI_URL);
        when(supplierConfiguration.getSupplierUrl(MURATA)).thenReturn(MU_URL);
        when(restTemplate.postForEntity(eq(TI_URL + "/api/transaction/reserve"), any(Map.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("reservationId", UUID.randomUUID().toString())));
        when(restTemplate.postForEntity(eq(MU_URL + "/api/transaction/reserve"), any(Map.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("timeout", new SocketTimeoutException("read timed out")));

        CompletionResult result = service.attemptCompletion(txn.getId());

        assertEquals(CompletionResult.RETRYABLE, result);
        assertEquals(TransactionStatus.RETRYING, txn.getStatus());
        // The already-reserved item is kept for the next attempt, NOT rolled back.
        assertNotNull(txn.getItems().get(0).getReservationId());
    }

    @Test
    void attemptCompletion_businessRejectionDuringReserve_returnsTerminal() {
        when(supplierConfiguration.getSupplierUrl(TI)).thenReturn(TI_URL);
        when(restTemplate.postForEntity(eq(TI_URL + "/api/transaction/reserve"), any(Map.class), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null, null, null));

        CompletionResult result = service.attemptCompletion(txn.getId());

        assertEquals(CompletionResult.TERMINAL_FAILED, result);
    }

    @Test
    void abort_rollsBackReservedItems_andMarksFailed() {
        txn.getItems().get(0).setReservationId(UUID.randomUUID());
        txn.getItems().get(1).setReservationId(UUID.randomUUID());
        txn.setStatus(TransactionStatus.RETRYING);

        when(supplierConfiguration.getSupplierUrl(TI)).thenReturn(TI_URL);
        when(supplierConfiguration.getSupplierUrl(MURATA)).thenReturn(MU_URL);
        when(restTemplate.postForEntity(any(String.class), any(Map.class), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());

        Transaction result = service.abort(txn.getId());

        assertEquals(TransactionStatus.FAILED, result.getStatus());
        verify(restTemplate).postForEntity(eq(TI_URL + "/api/transaction/rollback"), any(Map.class), eq(Void.class));
        verify(restTemplate).postForEntity(eq(MU_URL + "/api/transaction/rollback"), any(Map.class), eq(Void.class));
    }
}
