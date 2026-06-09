package org.gerbitpcb.broker.domain;

public enum TransactionStatus {
    PENDING,
    PREPARED,
    RETRYING,
    PARTIALLY_COMMITTED,
    COMMITTED,
    ROLLED_BACK,
    FAILED
}
