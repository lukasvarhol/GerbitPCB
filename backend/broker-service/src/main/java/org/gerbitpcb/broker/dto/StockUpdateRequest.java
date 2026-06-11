package org.gerbitpcb.broker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record StockUpdateRequest(@NotBlank String sku, @NotBlank String supplier, @PositiveOrZero int availableStock){
}
			       
