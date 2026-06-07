package org.gerbitpcb.broker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record StockUpdateRequest(@NotBlank String sku, @NotBlank String supplier, @Positive int availableStock){
}
			       
