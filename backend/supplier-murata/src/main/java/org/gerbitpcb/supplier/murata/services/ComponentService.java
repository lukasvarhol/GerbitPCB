package org.gerbitpcb.supplier.murata.services;

import org.gerbitpcb.supplier.murata.domain.Component;
import org.gerbitpcb.supplier.murata.domain.ComponentDto;
import org.gerbitpcb.supplier.murata.domain.Reservation;
import org.gerbitpcb.supplier.murata.domain.ReservationStatus;
import org.gerbitpcb.supplier.murata.exceptions.OutOfStockException;
import org.gerbitpcb.supplier.murata.repository.ComponentRepository;
import org.gerbitpcb.supplier.murata.repository.ReservationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class ComponentService {

    private final ComponentRepository componentRepository;
    private final ReservationRepository reservationRepository;

    /**
     * How long a RESERVED reservation may live before the sweeper releases it.
     * MUST be >= the broker's async retry window (Phase 4), otherwise this self-defense
     * sweep would release stock from a broker that is still legitimately retrying.
     */
    @Value("${supplier.reservation.ttl:PT20M}")
    private Duration reservationTtl = Duration.ofMinutes(20);

    public ComponentService(ComponentRepository componentRepository, ReservationRepository reservationRepository) {
        this.componentRepository = componentRepository;
        this.reservationRepository = reservationRepository;
    }

    @Transactional(readOnly = true)
    public List<ComponentDto> getAllComponents() {
        return componentRepository.findAll().stream()
                .map(ComponentDto::fromEntity)
                .toList();
    }

    @Transactional
    public UUID reserve(String sku, int quantity) {
        if (quantity <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be positive");
        }

        Component component = componentRepository.findBySku(sku)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Component not found"));

        if (component.getAvailableStock() < quantity) {
            throw new OutOfStockException("Insufficient stock for SKU: " + sku);
        }

        component.setAvailableStock(component.getAvailableStock() - quantity);
        component.setReservedStock(component.getReservedStock() + quantity);

        Reservation reservation = Reservation.builder()
                .component(component)
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .createdAt(Instant.now())
                .build();

        reservationRepository.save(reservation);
        return reservation.getId();
    }

    @Transactional
    public void commit(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));

        if (reservation.getStatus() == ReservationStatus.COMMITTED
                || reservation.getStatus() == ReservationStatus.ROLLED_BACK) {
            return;
        }

        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation not in RESERVED state");
        }

        Component component = reservation.getComponent();
        int quantity = reservation.getQuantity();

        if (component.getReservedStock() < quantity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reserved stock is lower than reservation");
        }

        component.setReservedStock(component.getReservedStock() - quantity);
        reservation.setStatus(ReservationStatus.COMMITTED);
    }

    /**
     * Rollback Logic:
     *   1. True Idempotency:
     *      If we already rolled this back, do nothing.
     *      This allows the caller to safely retry rollbacks without worrying about side effects.
     *
     *   2. Standard Rollback (Phase 1 Abortion):
     *      If the reservation is still in RESERVED state, we simply move the stock back from reserved to available.
     *      This is the normal rollback path when Phase 1 fails and we never moved to Phase 2.
     *
     *   3. Compensating Transaction (Saga Pattern for Split-Brain):
     *      If the reservation is already COMMITTED, it means we completed Phase 1 and moved to Phase 2,
     *      but then something failed (like a crash or network issue) before the caller could confirm the commit.
     *      In this case, we can't move stock from reserved to available because reserved is already 0. Instead, we just refund the available stock.
     *      This is a compensating action that ensures eventual consistency, even in the face of failures during the commit phase.
     *      see README docs for more in depth explanation of this edge case.
     *
     *   4. Mark as canceled:
     *      In both cases, we mark the reservation as ROLLED_BACK to indicate that it has been handled and should not be processed again.
     */
    @Transactional
    public void rollback(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));

        // 1. True Idempotency: If we already rolled this back, do nothing.
        if (reservation.getStatus() == ReservationStatus.ROLLED_BACK) {
            return;
        }

        Component component = reservation.getComponent();
        int quantity = reservation.getQuantity();

        // 2. Standard Rollback (Phase 1 Abortion)
        if (reservation.getStatus() == ReservationStatus.RESERVED) {
            if (component.getReservedStock() < quantity) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Reserved stock is lower than reservation");
            }
            // Move stock from reserved back to available
            component.setReservedStock(component.getReservedStock() - quantity);
            component.setAvailableStock(component.getAvailableStock() + quantity);
        }
        // 3. Compensating Transaction (Saga Pattern for Split-Brain)
        else if (reservation.getStatus() == ReservationStatus.COMMITTED) {
            // Reserved stock is already 0. Just refund the available stock.
            component.setAvailableStock(component.getAvailableStock() + quantity);
        }
        else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Unknown reservation state: " + reservation.getStatus());
        }

        // 4. Mark as canceled
        reservation.setStatus(ReservationStatus.ROLLED_BACK);
    }

    /**
     * Edge case:
     * If the broker service crashes, gets disconnected due to a network partition,
     * or fails after completing Phase 1 but before it sends Phase 2, those items would be stuck in a RESERVED status forever.
     *
     * Solution:
     * This method runs every 5 minutes to clean up any reservations that have been in the
     * RESERVED state for longer than the configured TTL (default 20 minutes).
     */

    @Scheduled(fixedRate = 300000)
    @Transactional
    public void cleanupStaleReservations() {
        Instant cutoff = Instant.now().minus(reservationTtl);
        List<Reservation> staleReservations = reservationRepository
                .findByStatusAndCreatedAtBefore(ReservationStatus.RESERVED, cutoff);

        for (Reservation reservation : staleReservations) {
            Component component = reservation.getComponent();
            int quantity = reservation.getQuantity();

            if (component.getReservedStock() >= quantity) {
                component.setReservedStock(component.getReservedStock() - quantity);
                component.setAvailableStock(component.getAvailableStock() + quantity);
            }
            reservation.setStatus(ReservationStatus.ROLLED_BACK);
        }
    }
}
