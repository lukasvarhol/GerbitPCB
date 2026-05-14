package org.gerbitpcb.broker.services;

import org.gerbitpcb.broker.config.SupplierConfiguration;
import org.gerbitpcb.broker.domain.AuditEntry;
import org.gerbitpcb.broker.domain.Transaction;
import org.gerbitpcb.broker.domain.TransactionItem;
import org.gerbitpcb.broker.domain.TransactionStatus;
import org.gerbitpcb.broker.dto.CreateTransactionRequest;
import org.gerbitpcb.broker.exceptions.InvalidTransactionStateException;
import org.gerbitpcb.broker.exceptions.TransactionNotFoundException;
import org.gerbitpcb.broker.repository.TransactionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

@Service
public class BrokerOrchestrationService {

    private final TransactionRepository transactionRepository;
    private final RestTemplate restTemplate;
    private final SupplierConfiguration supplierConfiguration;

    public BrokerOrchestrationService(TransactionRepository transactionRepository, 
                                      RestTemplate restTemplate,
                                      SupplierConfiguration supplierConfiguration) {
        this.transactionRepository = transactionRepository;
        this.restTemplate = restTemplate;
        this.supplierConfiguration = supplierConfiguration;
    }

    @Transactional
    public Transaction createTransaction(CreateTransactionRequest request) {
        Transaction txn = new Transaction();
        txn.setCustomerName(request.customerName());
        txn.setStatus(TransactionStatus.PENDING);

        List<TransactionItem> items = new ArrayList<>();
        for (CreateTransactionRequest.Item it : request.items()) {
            TransactionItem ti = new TransactionItem();
            ti.setSupplier(it.supplier());
            ti.setSku(it.sku());
            ti.setQuantity(it.quantity());
            ti.setUnitPrice(it.unitPrice());
            ti.setTransaction(txn);
            items.add(ti);
        }
        txn.setItems(items);

        // Attempt phase 1: reserve on each supplier
        List<TransactionItem> reserved = new ArrayList<>();

        int step = 0;
        for (TransactionItem it : txn.getItems()) {
            step++;
            String supplier = it.getSupplier();
            String base = supplierConfiguration.getSupplierUrl(supplier);
            String url = base + "/api/transaction/reserve";
            Map<String, Object> req = Map.of("sku", it.getSku(), "quantity", it.getQuantity());
            try {
                ResponseEntity<Map> resp = restTemplate.postForEntity(url, req, Map.class);
                Map body = resp.getBody();
                if (body != null && body.containsKey("reservationId")) {
                    String id = body.get("reservationId").toString();
                    UUID rid = UUID.fromString(id);
                    it.setReservationId(rid);
                    reserved.add(it);
                    AuditEntry a = new AuditEntry();
                    a.setStepId(step);
                    a.setPhase("PREPARE");
                    a.setSupplier(supplier);
                    a.setStatus("SUCCESS");
                    a.setTimestamp(Instant.now());
                    a.setReservationId(rid);
                    txn.addAudit(a);
                } else {
                    AuditEntry a = new AuditEntry();
                    a.setStepId(step);
                    a.setPhase("PREPARE");
                    a.setSupplier(supplier);
                    a.setStatus("FAILED");
                    a.setTimestamp(Instant.now());
                    a.setFailureReason("NO_RESERVATION_ID");
                    txn.addAudit(a);
                    // rollback previously reserved
                    rollbackReserved(reserved);
                    txn.setStatus(TransactionStatus.FAILED);
                    return transactionRepository.save(txn);
                }
            } catch (HttpClientErrorException ex) {
                AuditEntry a = new AuditEntry();
                a.setStepId(step);
                a.setPhase("PREPARE");
                a.setSupplier(supplier);
                a.setStatus("FAILED");
                a.setTimestamp(Instant.now());
                a.setFailureReason(ex.getStatusCode().toString());
                txn.addAudit(a);
                rollbackReserved(reserved);
                txn.setStatus(TransactionStatus.FAILED);
                return transactionRepository.save(txn);
            } catch (RestClientException ex) {
                AuditEntry a = new AuditEntry();
                a.setStepId(step);
                a.setPhase("PREPARE");
                a.setSupplier(supplier);
                a.setStatus("FAILED");
                a.setTimestamp(Instant.now());
                a.setFailureReason("SUPPLIER_UNREACHABLE");
                txn.addAudit(a);
                rollbackReserved(reserved);
                txn.setStatus(TransactionStatus.FAILED);
                return transactionRepository.save(txn);
            }
        }

        txn.setStatus(TransactionStatus.PREPARED);
        return transactionRepository.save(txn);
    }

    private void rollbackReserved(List<TransactionItem> reserved) {
        for (TransactionItem it : reserved) {
            String base = supplierConfiguration.getSupplierUrl(it.getSupplier());
            String url = base + "/api/transaction/rollback";
            if (it.getReservationId() == null) continue;
            Map<String, Object> req = Map.of("reservationId", it.getReservationId().toString());
            try {
                restTemplate.postForEntity(url, req, Void.class);
            } catch (Exception ignore) {
                // best-effort rollback; record kept in audit by caller
            }
        }
    }

    @Transactional
    public Transaction commitTransaction(UUID transactionId) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));
        if (txn.getStatus() != TransactionStatus.PREPARED) {
            throw new InvalidTransactionStateException("Transaction not in PREPARED state, current state: " + txn.getStatus());
        }

        int step = 0;
        for (TransactionItem it : txn.getItems()) {
            step++;
            if (it.getReservationId() == null) continue;
            String base = supplierConfiguration.getSupplierUrl(it.getSupplier());
            String url = base + "/api/transaction/commit";
            Map<String, Object> req = Map.of("reservationId", it.getReservationId().toString());
            try {
                restTemplate.postForEntity(url, req, Void.class);
                AuditEntry a = new AuditEntry();
                a.setStepId(step);
                a.setPhase("COMMIT");
                a.setSupplier(it.getSupplier());
                a.setStatus("SUCCESS");
                a.setTimestamp(Instant.now());
                a.setReservationId(it.getReservationId());
                txn.addAudit(a);
            } catch (Exception ex) {
                AuditEntry a = new AuditEntry();
                a.setStepId(step);
                a.setPhase("COMMIT");
                a.setSupplier(it.getSupplier());
                a.setStatus("FAILED");
                a.setTimestamp(Instant.now());
                a.setReservationId(it.getReservationId());
                a.setFailureReason("COMMIT_FAILED");
                txn.addAudit(a);
                txn.setStatus(TransactionStatus.FAILED);
                return transactionRepository.save(txn);
            }
        }

        txn.setStatus(TransactionStatus.COMMITTED);
        return transactionRepository.save(txn);
    }

    @Transactional
    public Transaction rollbackTransaction(UUID transactionId) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));

        int step = 0;
        for (TransactionItem it : txn.getItems()) {
            step++;
            if (it.getReservationId() == null) continue;
            String base = supplierConfiguration.getSupplierUrl(it.getSupplier());
            String url = base + "/api/transaction/rollback";
            Map<String, Object> req = Map.of("reservationId", it.getReservationId().toString());
            try {
                restTemplate.postForEntity(url, req, Void.class);
                AuditEntry a = new AuditEntry();
                a.setStepId(step);
                a.setPhase("ROLLBACK");
                a.setSupplier(it.getSupplier());
                a.setStatus("SUCCESS");
                a.setTimestamp(Instant.now());
                a.setReservationId(it.getReservationId());
                txn.addAudit(a);
            } catch (Exception ex) {
                AuditEntry a = new AuditEntry();
                a.setStepId(step);
                a.setPhase("ROLLBACK");
                a.setSupplier(it.getSupplier());
                a.setStatus("FAILED");
                a.setTimestamp(Instant.now());
                a.setReservationId(it.getReservationId());
                a.setFailureReason("ROLLBACK_FAILED");
                txn.addAudit(a);
            }
        }

        txn.setStatus(TransactionStatus.ROLLED_BACK);
        return transactionRepository.save(txn);
    }

    public Transaction getTransaction(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));
    }
}


