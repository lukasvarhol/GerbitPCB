package org.gerbitpcb.broker.domain;

public enum TransactionStatus {
    PENDING,
    PREPARED,
    PARTIALLY_COMMITTED,
    COMMITTED,
    ROLLED_BACK,
    FAILED
}
