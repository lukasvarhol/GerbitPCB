package org.gerbitpcb.supplier.ti.repository;

import org.gerbitpcb.supplier.ti.domain.Reservation;
import org.gerbitpcb.supplier.ti.domain.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    List<Reservation> findByStatusAndCreatedAtBefore(ReservationStatus status, Instant cutoff);
}
