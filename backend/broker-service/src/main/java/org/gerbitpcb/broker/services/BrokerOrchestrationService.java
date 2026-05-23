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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

@Service
public class BrokerOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(BrokerOrchestrationService.class);
    private static final String PHASE_PREPARE = "PREPARE";
    private static final String PHASE_COMMIT = "COMMIT";
    private static final String PHASE_ROLLBACK = "ROLLBACK";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

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

    public Transaction createTransaction(CreateTransactionRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("Transaction request must include at least one item");
        }

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
        txn = transactionRepository.save(txn);

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
                Object reservationId = body == null ? null : body.get("reservationId");
                if (reservationId == null) {
                    throw new IllegalArgumentException("NO_RESERVATION_ID");
                }

                UUID rid;
                try {
                    rid = UUID.fromString(reservationId.toString());
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("INVALID_RESERVATION_ID");
                }

                it.setReservationId(rid);
                reserved.add(it);
                txn.addAudit(createAudit(step, PHASE_PREPARE, supplier, STATUS_SUCCESS, rid, null));
                txn = transactionRepository.save(txn);
            } catch (IllegalArgumentException | HttpMessageConversionException | RestClientException ex) {

                String rawMessage = ex.getMessage();
                if (ex instanceof RestClientException) {
                    if (ex.getCause() instanceof HttpMessageConversionException) {
                        rawMessage = "MALFORMED_RESPONSE: " + ex.getCause().getMessage();
                    } else if (rawMessage != null && rawMessage.contains("Could not extract response")) {
                        // THIS catches the exact HTML content-type error you found in the debugger!
                        rawMessage = "MALFORMED_RESPONSE: " + rawMessage;
                    }
                } else if (ex instanceof HttpMessageConversionException) {
                    rawMessage = "MALFORMED_RESPONSE: " + rawMessage;
                }

                // Truncate the string to 250 characters max
                String safeFailureReason = (rawMessage != null && rawMessage.length() > 250)
                        ? rawMessage.substring(0, 250)
                        : rawMessage;

                // Execute the rollback and save (Written ONLY ONCE)
                txn.addAudit(createAudit(step, PHASE_PREPARE, supplier, STATUS_FAILED, null, safeFailureReason));
                rollbackReserved(reserved, txn, step);
                txn.setStatus(TransactionStatus.FAILED);
                return transactionRepository.save(txn);
            }
        }

        txn.setStatus(TransactionStatus.PREPARED);
        return transactionRepository.save(txn);
    }

    private void rollbackReserved(List<TransactionItem> reserved, Transaction txn, int step) {
        for (TransactionItem it : reserved) {
            if (it.getReservationId() == null) {
                continue;
            }

            String base = supplierConfiguration.getSupplierUrl(it.getSupplier());
            String url = base + "/api/transaction/rollback";
            Map<String, Object> req = Map.of("reservationId", it.getReservationId());

            try {
                restTemplate.postForEntity(url, req, Void.class);
                txn.addAudit(createAudit(step, PHASE_ROLLBACK, it.getSupplier(), STATUS_SUCCESS, it.getReservationId(), null));
                it.setReservationId(null);
            } catch (Exception ex) {
                log.error("Failed to rollback supplier reservation. supplier={}, transactionId={}, reservationId={}",
                        it.getSupplier(), txn.getId(), it.getReservationId(), ex);
                txn.addAudit(createAudit(step, PHASE_ROLLBACK, it.getSupplier(), STATUS_FAILED, it.getReservationId(), ex.getMessage()));
            }
            transactionRepository.save(txn);
        }
    }

    public Transaction commitTransaction(UUID transactionId) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));
        if (txn.getStatus() != TransactionStatus.PREPARED && txn.getStatus() != TransactionStatus.PARTIALLY_COMMITTED) {
            throw new InvalidTransactionStateException("Transaction not in PREPARED/PARTIALLY_COMMITTED state, current state: " + txn.getStatus());
        }

        int step = 0;
        boolean partialFailure = false;
        for (TransactionItem it : txn.getItems()) {
            step++;
            if (it.getReservationId() == null) {
                continue;
            }
            String base = supplierConfiguration.getSupplierUrl(it.getSupplier());
            String url = base + "/api/transaction/commit";
            Map<String, Object> req = Map.of("reservationId", it.getReservationId().toString());
            try {
                restTemplate.postForEntity(url, req, Void.class);
                txn.addAudit(createAudit(step, PHASE_COMMIT, it.getSupplier(), STATUS_SUCCESS, it.getReservationId(), null));
                it.setReservationId(null);
                transactionRepository.save(txn);
            } catch (Exception ex) {
                txn.addAudit(createAudit(step, PHASE_COMMIT, it.getSupplier(), STATUS_FAILED, it.getReservationId(), "COMMIT_FAILED"));
                partialFailure = true;
                break;
            }
        }

        txn.setStatus(partialFailure ? TransactionStatus.PARTIALLY_COMMITTED : TransactionStatus.COMMITTED);
        return transactionRepository.save(txn);
    }

    public Transaction rollbackTransaction(UUID transactionId) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));
        if (txn.getStatus() == TransactionStatus.COMMITTED) {
            throw new InvalidTransactionStateException("Committed transaction cannot be rolled back");
        }
        if (txn.getStatus() != TransactionStatus.PREPARED
                && txn.getStatus() != TransactionStatus.PARTIALLY_COMMITTED
                && txn.getStatus() != TransactionStatus.FAILED) {
            throw new InvalidTransactionStateException("Transaction not in rollback-allowed state, current state: " + txn.getStatus());
        }

        int step = 0;
        boolean rollbackFailed = false;
        for (TransactionItem it : txn.getItems()) {
            step++;
            if (it.getReservationId() == null) {
                continue;
            }
            String base = supplierConfiguration.getSupplierUrl(it.getSupplier());
            String url = base + "/api/transaction/rollback";
            Map<String, Object> req = Map.of("reservationId", it.getReservationId().toString());
            try {
                restTemplate.postForEntity(url, req, Void.class);
                txn.addAudit(createAudit(step, PHASE_ROLLBACK, it.getSupplier(), STATUS_SUCCESS, it.getReservationId(), null));
                it.setReservationId(null);
            } catch (Exception ex) {
                txn.addAudit(createAudit(step, PHASE_ROLLBACK, it.getSupplier(), STATUS_FAILED, it.getReservationId(), "ROLLBACK_FAILED"));
                rollbackFailed = true;
            }
            transactionRepository.save(txn);
        }

        txn.setStatus(rollbackFailed ? TransactionStatus.FAILED : TransactionStatus.ROLLED_BACK);
        return transactionRepository.save(txn);
    }

    public Transaction getTransaction(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));
    }

    private AuditEntry createAudit(int stepId, String phase, String supplier, String status, UUID reservationId, String failureReason) {
        AuditEntry auditEntry = new AuditEntry();
        auditEntry.setStepId(stepId);
        auditEntry.setPhase(phase);
        auditEntry.setSupplier(supplier);
        auditEntry.setStatus(status);
        auditEntry.setTimestamp(Instant.now());
        auditEntry.setReservationId(reservationId);
        auditEntry.setFailureReason(failureReason);
        return auditEntry;
    }
}
