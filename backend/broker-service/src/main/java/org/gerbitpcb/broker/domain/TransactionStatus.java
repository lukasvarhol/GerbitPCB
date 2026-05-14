package org.gerbitpcb.broker.domain;

public enum TransactionStatus {
    PENDING,
    PREPARED,
    COMMITTED,
    ROLLED_BACK,
    FAILED
}

