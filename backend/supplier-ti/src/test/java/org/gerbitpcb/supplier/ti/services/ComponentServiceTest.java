package org.gerbitpcb.supplier.ti.services;

import org.gerbitpcb.supplier.ti.domain.Component;
import org.gerbitpcb.supplier.ti.domain.Reservation;
import org.gerbitpcb.supplier.ti.domain.ReservationStatus;
import org.gerbitpcb.supplier.ti.exceptions.OutOfStockException;
import org.gerbitpcb.supplier.ti.repository.ComponentRepository;
import org.gerbitpcb.supplier.ti.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComponentServiceTest {

    @Mock
    private ComponentRepository componentRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private BrokerNotificationService brokerNotificationService;

    @InjectMocks
    private ComponentService componentService;

    private Component component;
    private Reservation reservation;
    private UUID reservationId;

    @BeforeEach
    void setUp() {
        component = new Component();
        component.setId(UUID.randomUUID());
        component.setSku("TEST-SKU");
        component.setAvailableStock(100);
        component.setReservedStock(0);
        component.setPrice(new BigDecimal("1.00"));

        reservationId = UUID.randomUUID();
        reservation = Reservation.builder()
                .id(reservationId)
                .component(component)
                .quantity(10)
                .status(ReservationStatus.RESERVED)
                .build();
    }

    @Test
    void reserve_Success_DecreasesAvailableAndCreatesReservation() {
        when(componentRepository.findBySku("TEST-SKU")).thenReturn(Optional.of(component));
        
        componentService.reserve("TEST-SKU", 10);
        
        assertEquals(90, component.getAvailableStock());
        assertEquals(10, component.getReservedStock());
        verify(reservationRepository, times(1)).save(any(Reservation.class));
    }

    @Test
    void reserve_OutOfStock_ThrowsException() {
        when(componentRepository.findBySku("TEST-SKU")).thenReturn(Optional.of(component));
        
        assertThrows(OutOfStockException.class, () -> componentService.reserve("TEST-SKU", 150));
        
        assertEquals(100, component.getAvailableStock()); // Unchanged
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void commit_Success_FinalizesReservation() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        
        component.setReservedStock(10); // Represents state after reserve()
        
        componentService.commit(reservationId);
        
        assertEquals(0, component.getReservedStock());
        assertEquals(ReservationStatus.COMMITTED, reservation.getStatus());
    }
    
    @Test
    void commit_AlreadyRolledBack_NoOp() {
        reservation.setStatus(ReservationStatus.ROLLED_BACK);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertDoesNotThrow(() -> componentService.commit(reservationId));
        assertEquals(ReservationStatus.ROLLED_BACK, reservation.getStatus());
    }

    @Test
    void rollback_Success_ReturnsStockToAvailable() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        
        component.setAvailableStock(90);
        component.setReservedStock(10); // Represents state after reserve()
        
        componentService.rollback(reservationId);
        
        assertEquals(100, component.getAvailableStock());
        assertEquals(0, component.getReservedStock());
        assertEquals(ReservationStatus.ROLLED_BACK, reservation.getStatus());
    }

    @Test
    void cleanupStaleReservations_ReleasesStockAndMarksRolledBack() {
        reservation.setCreatedAt(Instant.now().minusSeconds(600));
        reservation.setStatus(ReservationStatus.RESERVED);
        component.setReservedStock(10);
        component.setAvailableStock(90);

        when(reservationRepository.findByStatusAndCreatedAtBefore(eq(ReservationStatus.RESERVED), any(Instant.class)))
                .thenReturn(List.of(reservation));

        componentService.cleanupStaleReservations();

        assertEquals(0, component.getReservedStock());
        assertEquals(100, component.getAvailableStock());
        assertEquals(ReservationStatus.ROLLED_BACK, reservation.getStatus());
        verify(reservationRepository, times(1))
                .findByStatusAndCreatedAtBefore(eq(ReservationStatus.RESERVED), any(Instant.class));
    }
}
