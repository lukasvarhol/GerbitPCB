package org.gerbitpcb.supplier.murata.repository;

import org.gerbitpcb.supplier.murata.domain.Reservation;
import org.gerbitpcb.supplier.murata.domain.ReservationStatus;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    List<Reservation> findByStatusAndCreatedAtBefore(ReservationStatus status, Instant cutoff);
}
