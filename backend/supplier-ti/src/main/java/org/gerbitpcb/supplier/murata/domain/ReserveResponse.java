package org.gerbitpcb.supplier.murata.domain;

import java.util.UUID;

/**
 * DTO (Data Transfer Object) for Phase 1: The Response.
 * 
 * Sent back to the Broker Service after a successful reserve. 
 * We return a newly generated reservationId so the Broker has a receipt/ticket 
 * to use later during Phase 2.
 */
public record ReserveResponse(UUID reservationId) {
}
