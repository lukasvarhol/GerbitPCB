package org.gerbitpcb.supplier.murata.repository;

import org.gerbitpcb.supplier.murata.domain.Reservation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
}

