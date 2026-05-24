package org.gerbitpcb.broker.controllers;

import org.gerbitpcb.broker.domain.Transaction;
import org.gerbitpcb.broker.domain.TransactionStatus;
import org.gerbitpcb.broker.dto.CreateTransactionRequest;
import org.gerbitpcb.broker.dto.CreateTransactionResponse;
import org.gerbitpcb.broker.dto.TransactionResponse;
import org.gerbitpcb.broker.services.BrokerOrchestrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final BrokerOrchestrationService brokerService;

    public TransactionController(BrokerOrchestrationService brokerService) {
        this.brokerService = brokerService;
    }

    /**
     * This endpoint initiates the transaction (PREPARE) and confirms the transaction (COMMIT) when all parties agree.
     * PARTIALLY_COMMITTED can only occur if the commit phase fails after a successful prepare, which is a rare edge case that can be resolved by the Sweep Job.
     * <p>
     * originally the two phases were separated into /prepare and /commit endpoints, but for better developer/user experience,
     * we combine them into one endpoint to achieve a more seamless transaction flow.
     * Two-Step Checkout vs Synchronous One-Step Checkout
     * @param request
     * @return ResponseEntity<CreateTransactionResponse>
     */

    @PostMapping
    public ResponseEntity<CreateTransactionResponse> create(@Valid @RequestBody CreateTransactionRequest request) {
        // Phase 1: The Broker negotiates the reservations
        Transaction txn = brokerService.createTransaction(request);

        // Phase 2: If Phase 1 succeeded, instantly finalize the commit
        if (txn.getStatus() == TransactionStatus.PREPARED) {
            txn = brokerService.commitTransaction(txn.getId());
        }

        // Determine the final HTTP status
        HttpStatus status;
        if (txn.getStatus() == TransactionStatus.COMMITTED) {
            status = HttpStatus.CREATED; // Perfect Success
        } else if (txn.getStatus() == TransactionStatus.PARTIALLY_COMMITTED) {
            status = HttpStatus.ACCEPTED; // Split-brain occurred, the Sweep Job will fix it later
        } else {
            status = HttpStatus.BAD_GATEWAY; // Phase 1 failed, or full rollback occurred
        }

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
