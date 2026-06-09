package org.gerbitpcb.broker.controllers;

import org.gerbitpcb.broker.domain.Transaction;
import org.gerbitpcb.broker.domain.TransactionStatus;
import org.gerbitpcb.broker.dto.CreateTransactionRequest;
import org.gerbitpcb.broker.dto.CreateTransactionResponse;
import org.gerbitpcb.broker.dto.TransactionResponse;
import org.gerbitpcb.broker.messaging.RetryQueue;
import org.gerbitpcb.broker.services.BrokerOrchestrationService;
import org.gerbitpcb.broker.services.BrokerOrchestrationService.CompletionResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final BrokerOrchestrationService brokerService;
    private final RetryQueue retryQueue;

    public TransactionController(BrokerOrchestrationService brokerService, RetryQueue retryQueue) {
        this.brokerService = brokerService;
        this.retryQueue = retryQueue;
    }

    /**
     * Synchronous checkout with async fallback (Phase 4).
     * Persists the order, then makes ONE synchronous completion attempt (Try + Confirm)
     *   201 COMMITTED - all suppliers reserved + committed synchronously.
     *   202 ACCEPTED - a supplier was unreachable; the order is queued and a background
     *       process retries for up to 15 minutes (the customer can leave). Status is RETRYING.
     *   502 BAD_GATEWAY - a deterministic business failure (e.g. out of stock); aborted.
     *
     */
    @PostMapping
    public ResponseEntity<CreateTransactionResponse> create(@Valid @RequestBody CreateTransactionRequest request) {
        UUID id = brokerService.createPending(request).getId();
        CompletionResult result = brokerService.attemptCompletion(id);

        HttpStatus status;
        switch (result) {
            case COMPLETED -> status = HttpStatus.CREATED;
            case RETRYABLE -> {
                retryQueue.enqueue(id);
                status = HttpStatus.ACCEPTED;
            }
            default -> {
                brokerService.abort(id);
                status = HttpStatus.BAD_GATEWAY;
            }
        }

        Transaction txn = brokerService.getTransaction(id);
        return ResponseEntity.status(status).body(new CreateTransactionResponse(txn.getId(), txn.getStatus()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> get(@PathVariable UUID id) {
        Transaction txn = brokerService.getTransaction(id);
        return ResponseEntity.ok(TransactionResponse.from(txn));
    }

    @PostMapping("/{id}/commit")
    public ResponseEntity<TransactionResponse> commit(@PathVariable UUID id) {
        Transaction txn = brokerService.commitTransaction(id);
        HttpStatus status = txn.getStatus() == TransactionStatus.COMMITTED
                ? HttpStatus.OK
                : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(TransactionResponse.from(txn));
    }

    @PostMapping("/{id}/rollback")
    public ResponseEntity<TransactionResponse> rollback(@PathVariable UUID id) {
        Transaction txn = brokerService.rollbackTransaction(id);
        HttpStatus status = txn.getStatus() == TransactionStatus.ROLLED_BACK
                ? HttpStatus.OK
                : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(TransactionResponse.from(txn));
    }
}
