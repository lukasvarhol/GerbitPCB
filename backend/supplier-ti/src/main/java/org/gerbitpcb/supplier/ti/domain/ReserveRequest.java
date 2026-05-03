package org.gerbitpcb.supplier.ti.domain;

/**
 * DTO (Data Transfer Object) for Phase 1: The Request.
 * 
 * Used by the external Broker Service to ask: "Please lock X amount of this SKU for me."
 * This separates the incoming HTTP JSON payload from our internal database models.
 */
public record ReserveRequest(
        String sku, int quantity
) {
}
