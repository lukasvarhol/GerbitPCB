package org.gerbitpcb.supplier.ti;

import org.gerbitpcb.supplier.ti.domain.Component;
import org.gerbitpcb.supplier.ti.domain.Reservation;
import org.gerbitpcb.supplier.ti.domain.ReservationStatus;
import org.gerbitpcb.supplier.ti.repository.ComponentRepository;
import org.gerbitpcb.supplier.ti.repository.ReservationRepository;
import org.gerbitpcb.supplier.ti.services.ComponentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false) // Disable security filters for isolated integration testing
@ActiveProfiles("test")
public class SupplierTiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ComponentRepository componentRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ComponentService componentService;

    private UUID testComponentId;
    private UUID testReservationId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        componentRepository.deleteAll();

        Component component = new Component();
        component.setSku("TEST-SKU");
        component.setName("Test Component");
        component.setPrice(new BigDecimal("10.00"));
        component.setAvailableStock(100);
        component.setReservedStock(0);
        Component savedComponent = componentRepository.save(component);
        testComponentId = savedComponent.getId();

        Reservation reservation = new Reservation();
        reservation.setComponent(savedComponent);
        reservation.setQuantity(10);
        reservation.setStatus(ReservationStatus.RESERVED);
        reservation.setCreatedAt(Instant.now());
        Reservation savedReservation = reservationRepository.save(reservation);
        testReservationId = savedReservation.getId();

        // Emulate that stock was reserved
        savedComponent.setAvailableStock(90);
        savedComponent.setReservedStock(10);
        componentRepository.save(savedComponent);
    }

    /**
     * TEST DESCRIPTION:
     * - What it does:
     *      Sends two identical commit requests ("double-tap") for the same reservation ID.
     * - What it expects:
     *      Both requests return HTTP 204 No Content.
     *      The available stock remains deducted correctly (90),
     *      reserved stock becomes 0,
     *      and the reservation status is COMMITTED.
     * - Why we test it:
     *      To ensure the commit endpoint is idempotent.
     *      If a caller retries a commit due to network issues, it should not deduct stock multiple times or throw an error.
     *
     * TEST FAILURE LOGS:
     * - we expected the wrong response from the commit rest call
     *      .andExpect(status().isOk());
      *     instead of the correct:
      *     .andExpect(status().isNoContent());
      *  This was because the commit endpoint is designed to return 204 No Content on success, not 200 OK.
     *   The test was failing with a 204 response when it expected 200
     */
    @Test
    void testCommit_DoubleTap_IsIdempotent() throws Exception {
        String requestJson = "{\"reservationId\":\"" + testReservationId + "\"}";

        // Action 1: First Commit
        mockMvc.perform(post("/api/transaction/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isNoContent());

        // Action 2: The "Double-Tap" (duplicate call should not fail and should not double deduct)
        mockMvc.perform(post("/api/transaction/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isNoContent());

        // Assertion
        Component componentResult = componentRepository.findById(testComponentId).get();
        assertEquals(90, componentResult.getAvailableStock());
        assertEquals(0, componentResult.getReservedStock());

        Reservation reservationResult = reservationRepository.findById(testReservationId).get();
        assertEquals(ReservationStatus.COMMITTED, reservationResult.getStatus());
    }

    /**
     * TEST DESCRIPTION: 
     * - What it does:
     *      Backdates an active reservation to simulate a timeout, then executes the cleanup job logic.
     * - What it expects:
     *      The reserved stock is returned to the available pool (100 total, 0 reserved),
     *      and the reservation is marked as ROLLED_BACK.
     * - Why we test it:
     *      To verify that the system is self-healing.
     *      If a distributed transaction stalls or the coordinator crashes,
     *      stale reservations should be rolled back automatically so stock isn't permanently locked.
     *
     * TEST FAILURE LOGS:
     * - Test was failing due to the reservation entity specifying @Column(updatable = false) on the createdAt field,
     *   which prevented us from backdating the timestamp for testing purposes.
     *      @Column(nullable = false, updatable = false)
     *      private Instant createdAt;
     *   The java object itself would change the createdAt value, but wouldn't persist to the test db.
     */
    @Test
    void testCronJob_TimeTravel_CleansDanglingReservations() {
        // Setup time travel: Backdate reservation to 10 minutes ago
        Reservation reservation = reservationRepository.findById(testReservationId).get();
        reservation.setCreatedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        reservationRepository.save(reservation);
        
        // Emulate that stock was reserved
        Component component = componentRepository.findById(testComponentId).get();
        component.setAvailableStock(90);
        component.setReservedStock(10);
        componentRepository.save(component);

        // Action: manually trigger test logic for cleaning up stuck records
        componentService.cleanupStaleReservations();

        // Assertion: Stock should be liberated
        Component componentResult = componentRepository.findById(testComponentId).get();
        assertEquals(100, componentResult.getAvailableStock());
        assertEquals(0, componentResult.getReservedStock());

        Reservation reservationResult = reservationRepository.findById(testReservationId).get();
        assertEquals(ReservationStatus.ROLLED_BACK, reservationResult.getStatus());
    }
}
