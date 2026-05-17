package org.gerbitpcb.broker.dto;

import org.gerbitpcb.broker.domain.TransactionStatus;
import java.util.UUID;

public record CreateTransactionResponse(UUID transactionId, TransactionStatus status) {}
