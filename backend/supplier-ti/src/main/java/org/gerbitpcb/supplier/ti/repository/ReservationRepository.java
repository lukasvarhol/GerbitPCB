package org.gerbitpcb.supplier.ti.repository;

import org.gerbitpcb.supplier.ti.domain.Reservation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
}

