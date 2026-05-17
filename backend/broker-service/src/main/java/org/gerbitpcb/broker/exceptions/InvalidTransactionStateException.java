package org.gerbitpcb.broker.exceptions;

/**
 * Thrown when attempting a transaction operation on a transaction in an invalid state.
 * For example, trying to commit a transaction that is not in PREPARED state.
 */
public class InvalidTransactionStateException extends RuntimeException {
    public InvalidTransactionStateException(String message) {
        super(message);
    }

    public InvalidTransactionStateException(String message, Throwable cause) {
        super(message, cause);
    }
}

