package org.gerbitpcb.broker.services;

import org.gerbitpcb.broker.config.SupplierConfiguration;
import org.gerbitpcb.broker.domain.AuditEntry;
import org.gerbitpcb.broker.domain.Transaction;
import org.gerbitpcb.broker.domain.TransactionItem;
import org.gerbitpcb.broker.domain.TransactionStatus;
import org.gerbitpcb.broker.dto.CreateTransactionRequest;
import org.gerbitpcb.broker.exceptions.InvalidTransactionStateException;
import org.gerbitpcb.broker.exceptions.TransactionNotFoundException;
import org.gerbitpcb.broker.messaging.RetryQueue;
import org.gerbitpcb.broker.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Orchestrates the Two-Phase Commit (2PC) protocol between the Broker and external Suppliers.
 * Acts as the Coordinator to ensure distributed transactions are atomic (all succeed or all fail).
 */
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

    private Clock clock = Clock.systemUTC();

    @Value("${app.retry.window:PT15M}")
    private Duration retryWindow = Duration.ofMinutes(15);

    private RetryQueue retryQueue;

    public BrokerOrchestrationService(TransactionRepository transactionRepository,
                                      RestTemplate restTemplate,
                                      SupplierConfiguration supplierConfiguration) {
        this.transactionRepository = transactionRepository;
        this.restTemplate = restTemplate;
        this.supplierConfiguration = supplierConfiguration;
    }

    @Autowired(required = false)
    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    @Autowired(required = false)
    public void setRetryQueue(RetryQueue retryQueue) {
        this.retryQueue = retryQueue;
    }

    /**
     * Persists a new transaction in PENDING with its 15-minute deadline, WITHOUT contacting any
     * supplier. The caller then drives it with {@link #attemptCompletion(UUID)}.
     */
    @Transactional
    public Transaction createPending(CreateTransactionRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("Transaction request must include at least one item");
        }
        Transaction txn = new Transaction();
        txn.setCustomerName(request.customerName());
        txn.setStatus(TransactionStatus.PENDING);
        txn.setDeadlineAt(Instant.now(clock).plus(retryWindow));

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
        return transactionRepository.save(txn);
    }

    /**
     * Phase 1 of 2PC: The PREPARE Phase.
     * Attempts to reserve inventory for all items in the request across multiple suppliers.
     * <p>
     * Usage: Called by the REST Controller when a customer submits a new order.
     * @param request The incoming transaction payload containing customer details and items.
     * @return The persisted Transaction (either PREPARED on success, or FAILED on failure).
     */
    public Transaction createTransaction(CreateTransactionRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("Transaction request must include at least one item");
        }

        // Step 1: Initialize the transaction state as PENDING in the database
        Transaction txn = new Transaction();
        txn.setCustomerName(request.customerName());
        txn.setStatus(TransactionStatus.PENDING);
        txn.setDeadlineAt(Instant.now(clock).plus(retryWindow));

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

        // Track items that successfully reserved so we can roll them back if a later item fails
        List<TransactionItem> reserved = new ArrayList<>();
        int step = 0;

        // Take a safe snapshot to prevent Hibernate ConcurrentModificationException
        List<TransactionItem> itemsSnapshot = new ArrayList<>(txn.getItems());

        // Step 2: Loop through items and ask Suppliers to lock inventory
        for (TransactionItem it : itemsSnapshot) {
            step++;
            String supplier = it.getSupplier();
            String base = supplierConfiguration.getSupplierUrl(supplier);
            String url = base + "/api/transaction/reserve";
            Map<String, Object> req = Map.of("sku", it.getSku(), "quantity", it.getQuantity());

            try {
                // Execute HTTP POST to Supplier
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

                // Step 3a: Success. Save the Reservation ID and audit the success.
                it.setReservationId(rid);
                reserved.add(it);
                txn.addAudit(createAudit(step, PHASE_PREPARE, supplier, STATUS_SUCCESS, rid, null));
                txn = transactionRepository.save(txn);

            } catch (IllegalArgumentException | HttpMessageConversionException | RestClientException ex) {
                // Step 3b: Failure. A supplier rejected the request, crashed, or timed out.
                String rawMessage = ex.getMessage();
                if (ex instanceof RestClientException) {
                    if (ex.getCause() instanceof HttpMessageConversionException) {
                        rawMessage = "MALFORMED_RESPONSE: " + ex.getCause().getMessage();
                    } else if (rawMessage != null && rawMessage.contains("Could not extract response")) {
                        rawMessage = "MALFORMED_RESPONSE: " + rawMessage;
                    }
                } else if (ex instanceof HttpMessageConversionException) {
                    rawMessage = "MALFORMED_RESPONSE: " + rawMessage;
                }

                // Truncate the string to 250 characters max to protect the database column limits
                String safeFailureReason = (rawMessage != null && rawMessage.length() > 250)
                        ? rawMessage.substring(0, 250)
                        : rawMessage;

                // Execute the tactical rollback for ONLY the items that succeeded prior to this failure
                txn.addAudit(createAudit(step, PHASE_PREPARE, supplier, STATUS_FAILED, null, safeFailureReason));
                rollbackReserved(reserved, txn, step);
                txn.setStatus(TransactionStatus.FAILED);
                return transactionRepository.save(txn);
            }
        }

        // Step 4: All suppliers agreed. Transaction is ready for Phase 2.
        txn.setStatus(TransactionStatus.PREPARED);
        return transactionRepository.save(txn);
    }

    /**
     * Tactical Helper Method: Reverses specific reservations mid-flight.
     * <p>
     * <b>Usage:</b> Called internally when a multi-item transaction fails halfway through processing.
     * @param reserved The list of items that successfully acquired a reservationId.
     * @param txn The current transaction entity.
     * @param step The current audit step counter.
     */
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

    /**
     * Phase 2 of 2PC: The COMMIT Phase.
     * Instructs all suppliers to permanently deduct the reserved inventory.
     * <p>
     * <b>Usage:</b> Called manually via REST endpoint, or automatically by the Sweep Job for crash recovery.
     * @param transactionId The UUID of the PREPARED transaction.
     * @return The finalized Transaction (COMMITTED, or PARTIALLY_COMMITTED if a split-brain occurs).
     */
    public Transaction commitTransaction(UUID transactionId) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));
        if (txn.getStatus() != TransactionStatus.PREPARED && txn.getStatus() != TransactionStatus.PARTIALLY_COMMITTED) {
            throw new InvalidTransactionStateException("Transaction not in PREPARED/PARTIALLY_COMMITTED state, current state: " + txn.getStatus());
        }

        int step = 0;
        boolean partialFailure = false;

        // Track successfully committed items to allow for Saga Pattern compensation if a race condition occurs
        List<TransactionItem> committed = new ArrayList<>();
        List<TransactionItem> itemsSnapshot = new ArrayList<>(txn.getItems());

        for (TransactionItem it : itemsSnapshot) {
            step++;
            if (it.getReservationId() == null) {
                continue;
            }
            String base = supplierConfiguration.getSupplierUrl(it.getSupplier());
            String url = base + "/api/transaction/commit";
            Map<String, Object> req = Map.of("reservationId", it.getReservationId().toString());

            try {
                // Step 1: Tell the supplier to permanently commit the reservation
                restTemplate.postForEntity(url, req, Void.class);
                txn.addAudit(createAudit(step, PHASE_COMMIT, it.getSupplier(), STATUS_SUCCESS, it.getReservationId(), null));
                committed.add(it);
                transactionRepository.save(txn);

            } catch (RestClientResponseException ex) {
                int statusCode = ex.getStatusCode().value();

                // Step 2a: RACE CONDITION (Saga Pattern Defense)
                // If the supplier already auto-cleaned the reservation (404/409), we must abort.
                // We pass the 'committed' list to rollbackReserved to reverse any items that ALREADY committed in this loop.
                if (statusCode == 404 || statusCode == 409) {
                    txn.addAudit(createAudit(step, PHASE_COMMIT, it.getSupplier(), STATUS_FAILED, it.getReservationId(),
                            "EXPIRED_RACE_CONDITION: " + statusCode));
                    rollbackReserved(committed, txn, step);
                    txn.setStatus(TransactionStatus.FAILED);
                    return transactionRepository.save(txn);
                }

                // Step 2b: Standard Network Failure (Split-Brain)
                txn.addAudit(createAudit(step, PHASE_COMMIT, it.getSupplier(), STATUS_FAILED, it.getReservationId(),
                        "COMMIT_FAILED: " + statusCode));
                partialFailure = true;
                break;

            } catch (Exception ex) {
                txn.addAudit(createAudit(step, PHASE_COMMIT, it.getSupplier(), STATUS_FAILED, it.getReservationId(), "COMMIT_FAILED"));
                partialFailure = true;
                break;
            }
        }

        // Step 3: Cleanup if entirely successful
        if (!partialFailure) {
            for (TransactionItem it : txn.getItems()) {
                it.setReservationId(null);
            }
        }

        // If one supplier committed but another failed, mark as PARTIALLY_COMMITTED so the Sweep Job can retry it later
        txn.setStatus(partialFailure ? TransactionStatus.PARTIALLY_COMMITTED : TransactionStatus.COMMITTED);
        return transactionRepository.save(txn);
    }

    /**
     * Full Transaction Rollback.
     * Aborts an entire transaction and frees all associated reservations across all suppliers.
     * <p>
     * <b>Usage:</b> Called by the Sweep Job for stuck PENDING transactions, or triggered by an admin/user cancellation.
     * @param transactionId The UUID of the transaction to cancel.
     * @return The updated Transaction marked as ROLLED_BACK (or FAILED if the network dropped during rollback).
     */
    public Transaction rollbackTransaction(UUID transactionId) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));
        if (txn.getStatus() == TransactionStatus.COMMITTED) {
            throw new InvalidTransactionStateException("Committed transaction cannot be rolled back");
        }
        if (txn.getStatus() != TransactionStatus.PREPARED
                && txn.getStatus() != TransactionStatus.PARTIALLY_COMMITTED
                && txn.getStatus() != TransactionStatus.FAILED
                && txn.getStatus() != TransactionStatus.PENDING) {
            throw new InvalidTransactionStateException("Transaction not in rollback-allowed state, current state: " + txn.getStatus());
        }

        int step = 0;
        boolean rollbackFailed = false;
        List<TransactionItem> itemsSnapshot = new ArrayList<>(txn.getItems());

        for (TransactionItem it : itemsSnapshot) {
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

    /**
     * Fetches a Transaction by ID.
     */
    public Transaction getTransaction(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));
    }

    /**
     * Helper Method: Generates structured Audit Logs for tracking the 2PC flow.
     */
    private AuditEntry createAudit(int stepId, String phase, String supplier, String status, UUID reservationId, String failureReason) {
        AuditEntry auditEntry = new AuditEntry();
        auditEntry.setStepId(stepId);
        auditEntry.setPhase(phase);
        auditEntry.setSupplier(supplier);
        auditEntry.setStatus(status);
        auditEntry.setTimestamp(Instant.now(clock));
        auditEntry.setReservationId(reservationId);
        auditEntry.setFailureReason(failureReason);
        return auditEntry;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Phase 4 (Level 2): idempotent async-retry primitives.
    // attemptCompletion() reconciles the CURRENT state of a transaction — reserve
    // any not-yet-reserved item (Try), then commit every reserved item (Confirm) —
    // so it is safe to re-run from the synchronous checkout, the async retry
    // consumer, or the recovery sweeper. abort() is the terminal global rollback
    // (saga compensation) used when the retry deadline is hit.
    // ──────────────────────────────────────────────────────────────────────────

    public enum CompletionResult { COMPLETED, RETRYABLE, TERMINAL_FAILED }

    @Transactional
    public CompletionResult attemptCompletion(UUID transactionId) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));
        txn.setAttemptCount(txn.getAttemptCount() + 1);

        List<TransactionItem> itemsSnapshot = new ArrayList<>(txn.getItems());
        int step = txn.getAuditTrail().size();

        // Phase 1 (Try): reserve any item that is not yet reserved.
        for (TransactionItem it : itemsSnapshot) {
            if (it.getReservationId() != null) {
                continue;
            }
            step++;
            try {
                UUID rid = reserveOne(it);
                it.setReservationId(rid);
                txn.addAudit(createAudit(step, PHASE_PREPARE, it.getSupplier(), STATUS_SUCCESS, rid, null));
                transactionRepository.save(txn);
            } catch (Exception ex) {
                txn.addAudit(createAudit(step, PHASE_PREPARE, it.getSupplier(), STATUS_FAILED, null, truncate(failureReason(ex))));
                if (isRetryable(ex)) {
                    txn.setStatus(TransactionStatus.RETRYING);
                    transactionRepository.save(txn);
                    return CompletionResult.RETRYABLE;
                }
                transactionRepository.save(txn);
                return CompletionResult.TERMINAL_FAILED;
            }
        }

        txn.setStatus(TransactionStatus.PREPARED);
        transactionRepository.save(txn);

        // Phase 2 (Confirm): commit every reserved item (supplier commit is idempotent).
        for (TransactionItem it : itemsSnapshot) {
            if (it.getReservationId() == null) {
                continue;
            }
            step++;
            try {
                commitOne(it);
                txn.addAudit(createAudit(step, PHASE_COMMIT, it.getSupplier(), STATUS_SUCCESS, it.getReservationId(), null));
                transactionRepository.save(txn);
            } catch (RestClientResponseException ex) {
                int statusCode = ex.getStatusCode().value();
                if (statusCode == 404 || statusCode == 409) {
                    // The supplier's reservation already expired (lost race) — caller aborts + compensates.
                    txn.addAudit(createAudit(step, PHASE_COMMIT, it.getSupplier(), STATUS_FAILED, it.getReservationId(),
                            "EXPIRED_RACE_CONDITION: " + statusCode));
                    txn.setStatus(TransactionStatus.PARTIALLY_COMMITTED);
                    transactionRepository.save(txn);
                    return CompletionResult.TERMINAL_FAILED;
                }
                txn.addAudit(createAudit(step, PHASE_COMMIT, it.getSupplier(), STATUS_FAILED, it.getReservationId(),
                        "COMMIT_FAILED: " + statusCode));
                txn.setStatus(TransactionStatus.PARTIALLY_COMMITTED);
                transactionRepository.save(txn);
                return isRetryable(ex) ? CompletionResult.RETRYABLE : CompletionResult.TERMINAL_FAILED;
            } catch (Exception ex) {
                txn.addAudit(createAudit(step, PHASE_COMMIT, it.getSupplier(), STATUS_FAILED, it.getReservationId(),
                        truncate(failureReason(ex))));
                txn.setStatus(TransactionStatus.PARTIALLY_COMMITTED);
                transactionRepository.save(txn);
                return isRetryable(ex) ? CompletionResult.RETRYABLE : CompletionResult.TERMINAL_FAILED;
            }
        }

        for (TransactionItem it : txn.getItems()) {
            it.setReservationId(null);
        }
        txn.setStatus(TransactionStatus.COMMITTED);
        transactionRepository.save(txn);
        return CompletionResult.COMPLETED;
    }

    /**
     * Terminal global rollback: release every reservation (saga-compensating any that already
     * committed — supplier rollback is idempotent and refunds committed stock) and mark the
     * transaction FAILED. Used when the retry deadline is hit, or on an unrecoverable failure.
     */
    @Transactional
    public Transaction abort(UUID transactionId) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + transactionId));

        int step = txn.getAuditTrail().size();
        for (TransactionItem it : new ArrayList<>(txn.getItems())) {
            if (it.getReservationId() == null) {
                continue;
            }
            step++;
            try {
                rollbackOne(it);
                txn.addAudit(createAudit(step, PHASE_ROLLBACK, it.getSupplier(), STATUS_SUCCESS, it.getReservationId(), null));
                it.setReservationId(null);
            } catch (Exception ex) {
                txn.addAudit(createAudit(step, PHASE_ROLLBACK, it.getSupplier(), STATUS_FAILED, it.getReservationId(), "ROLLBACK_FAILED"));
            }
            transactionRepository.save(txn);
        }

        txn.setStatus(TransactionStatus.FAILED);
        return transactionRepository.save(txn);
    }

    private UUID reserveOne(TransactionItem it) {
        String url = supplierConfiguration.getSupplierUrl(it.getSupplier()) + "/api/transaction/reserve";
        Map<String, Object> req = Map.of("sku", it.getSku(), "quantity", it.getQuantity());
        ResponseEntity<Map> resp = restTemplate.postForEntity(url, req, Map.class);
        Map body = resp.getBody();
        Object reservationId = body == null ? null : body.get("reservationId");
        if (reservationId == null) {
            throw new IllegalArgumentException("NO_RESERVATION_ID");
        }
        try {
            return UUID.fromString(reservationId.toString());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("INVALID_RESERVATION_ID");
        }
    }

    private void commitOne(TransactionItem it) {
        String url = supplierConfiguration.getSupplierUrl(it.getSupplier()) + "/api/transaction/commit";
        restTemplate.postForEntity(url, Map.of("reservationId", it.getReservationId().toString()), Void.class);
    }

    private void rollbackOne(TransactionItem it) {
        String url = supplierConfiguration.getSupplierUrl(it.getSupplier()) + "/api/transaction/rollback";
        restTemplate.postForEntity(url, Map.of("reservationId", it.getReservationId().toString()), Void.class);
    }

    /**
     * Classifies a failed supplier call: transient/transport failures and 5xx are retryable;
     * deterministic business failures (4xx, out-of-stock, malformed body, bad reservation id) are terminal.
     */
    private boolean isRetryable(Throwable ex) {
        if (ex instanceof RestClientResponseException rcre) {
            return rcre.getStatusCode().is5xxServerError();
        }
        if (ex instanceof HttpMessageConversionException) {
            return false;
        }
        if (ex instanceof IllegalArgumentException) {
            return false;
        }
        if (ex instanceof ResourceAccessException) {
            return true;
        }
        return ex instanceof RestClientException;
    }

    private String failureReason(Throwable ex) {
        String message = ex.getMessage();
        if (ex instanceof HttpMessageConversionException || ex.getCause() instanceof HttpMessageConversionException) {
            return "MALFORMED_RESPONSE: " + message;
        }
        return message;
    }

    private String truncate(String value) {
        return (value != null && value.length() > 250) ? value.substring(0, 250) : value;
    }

    /**
     * The Auto-Recovery Sweep Job.
     * Runs in the background to detect and heal transactions that stalled due to a Broker crash or network failure.
     * <p>
     * <b>Usage:</b> Automatically triggered by Spring Boot every 60 seconds (@Scheduled).
     */
    /**
     * Phase-4 recovery janitor (demoted): the queue + async consumer now own completion, so the
     * sweeper merely re-enqueues any non-terminal transaction stuck past the 1-minute buffer
     * (covering a broker crash between Phase 1 and the enqueue). The consumer then re-drives
     * {@link #attemptCompletion}, or aborts once the deadline passes. It reads only id/status, so
     * it never touches lazy collections outside a session. No-op when no RetryQueue is configured.
     */
    @Scheduled(fixedRate = 60000)
    public void sweepAndResumeStuckTransactions() {
        if (retryQueue == null) {
            return;
        }
        Instant cutoff = Instant.now(clock).minus(Duration.ofMinutes(1));

        List<TransactionStatus> stuckStatuses = List.of(
                TransactionStatus.PENDING,
                TransactionStatus.PREPARED,
                TransactionStatus.RETRYING,
                TransactionStatus.PARTIALLY_COMMITTED
        );

        for (Transaction txn : transactionRepository.findByStatusInAndStartedAtBefore(stuckStatuses, cutoff)) {
            log.info("Sweeper: re-enqueueing stuck transaction {} in state {}", txn.getId(), txn.getStatus());
            retryQueue.enqueue(txn.getId());
        }
    }

    public List<Transaction> getAllTransactions() {
	return transactionRepository.findAll();
    }
}
