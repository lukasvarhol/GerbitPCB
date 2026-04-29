package org.gerbitpcb.supplier.ti.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record ComponentDto(
        UUID id,
        String sku,
        String name,
        BigDecimal price,
        int availableStock,
        int reservedStock
) {
    public static ComponentDto fromEntity(Component component) {
        return new ComponentDto(
                component.getId(),
                component.getSku(),
                component.getName(),
                component.getPrice(),
                component.getAvailableStock(),
                component.getReservedStock()
        );
    }
}

