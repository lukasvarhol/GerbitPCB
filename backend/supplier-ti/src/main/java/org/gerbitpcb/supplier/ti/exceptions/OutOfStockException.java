package org.gerbitpcb.supplier.ti.exceptions;

public class OutOfStockException extends RuntimeException {
    public OutOfStockException(String message) {
        super(message);
    }
}

