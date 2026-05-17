package org.gerbitpcb.broker.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

public record CreateTransactionRequest(
        @NotBlank String customerName,
        @NotEmpty List<@Valid Item> items
) {
    public static record Item(
            @NotBlank String supplier,
            @NotBlank String sku,
            @Positive int quantity,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal unitPrice
    ) {}
}
