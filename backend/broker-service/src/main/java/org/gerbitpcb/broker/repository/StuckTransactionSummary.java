package org.gerbitpcb.broker.repository;

import org.gerbitpcb.broker.domain.TransactionStatus;

import java.util.UUID;

public interface StuckTransactionSummary {
    UUID getId();
    TransactionStatus getStatus();
}
