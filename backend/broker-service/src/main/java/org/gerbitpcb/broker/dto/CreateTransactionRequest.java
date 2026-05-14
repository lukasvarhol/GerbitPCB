package org.gerbitpcb.broker.dto;

import java.math.BigDecimal;
import java.util.List;

public record CreateTransactionRequest(String customerName, List<Item> items) {
    public static record Item(String supplier, String sku, int quantity, BigDecimal unitPrice) {}
}

