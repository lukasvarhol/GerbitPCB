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

    @PostMapping
    public ResponseEntity<CreateTransactionResponse> create(@Valid @RequestBody CreateTransactionRequest request) {
        Transaction txn = brokerService.createTransaction(request);
        HttpStatus status = txn.getStatus() == TransactionStatus.PREPARED
                ? HttpStatus.CREATED
                : HttpStatus.BAD_GATEWAY;
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
