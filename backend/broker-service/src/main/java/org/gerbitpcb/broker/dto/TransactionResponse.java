package org.gerbitpcb.broker.dto;

import org.gerbitpcb.broker.domain.Transaction;
import org.gerbitpcb.broker.domain.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String customerName,
        Instant startedAt,
        TransactionStatus status,
        List<Item> items,
        List<Audit> auditTrail
) {
    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getCustomerName(),
                transaction.getStartedAt(),
                transaction.getStatus(),
                transaction.getItems().stream()
                        .map(item -> new Item(item.getId(), item.getSupplier(), item.getSku(), item.getQuantity(), item.getUnitPrice(), item.getReservationId()))
                        .toList(),
                transaction.getAuditTrail().stream()
                        .map(audit -> new Audit(audit.getId(), audit.getStepId(), audit.getPhase(), audit.getSupplier(), audit.getStatus(), audit.getTimestamp(), audit.getReservationId(), audit.getFailureReason()))
                        .toList()
        );
    }

    public record Item(
            UUID id,
            String supplier,
            String sku,
            int quantity,
            java.math.BigDecimal unitPrice,
            UUID reservationId
    ) {}

    public record Audit(
            UUID id,
            int stepId,
            String phase,
            String supplier,
            String status,
            Instant timestamp,
            UUID reservationId,
            String failureReason
    ) {}
}
