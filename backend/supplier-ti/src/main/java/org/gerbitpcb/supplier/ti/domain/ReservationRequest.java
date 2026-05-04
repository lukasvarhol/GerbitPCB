package org.gerbitpcb.supplier.ti.domain;

import java.util.UUID;

/**
 * DTO (Data Transfer Object) for Phase 2: The Action.
 * 
 * Used by the external Broker Service when calling POST /commit or POST /rollback.
 * It provides the UUID ticket (generated during Phase 1) to definitively execute or abort a pending transaction.
 */
public record ReservationRequest(UUID reservationId) {
}
