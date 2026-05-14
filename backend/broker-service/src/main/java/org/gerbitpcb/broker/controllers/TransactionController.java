package org.gerbitpcb.broker.controllers;

import org.gerbitpcb.broker.domain.Transaction;
import org.gerbitpcb.broker.dto.CreateTransactionRequest;
import org.gerbitpcb.broker.dto.CreateTransactionResponse;
import org.gerbitpcb.broker.services.BrokerOrchestrationService;
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
    public ResponseEntity<CreateTransactionResponse> create(@RequestBody CreateTransactionRequest request) {
        Transaction txn = brokerService.createTransaction(request);
        return ResponseEntity.ok(new CreateTransactionResponse(txn.getId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Transaction> get(@PathVariable UUID id) {
        Transaction txn = brokerService.getTransaction(id);
        return ResponseEntity.ok(txn);
    }

    @PostMapping("/{id}/commit")
    public ResponseEntity<Void> commit(@PathVariable UUID id) {
        brokerService.commitTransaction(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/rollback")
    public ResponseEntity<Void> rollback(@PathVariable UUID id) {
        brokerService.rollbackTransaction(id);
        return ResponseEntity.ok().build();
    }
}

