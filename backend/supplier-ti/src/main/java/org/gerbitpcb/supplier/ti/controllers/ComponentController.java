package org.gerbitpcb.supplier.ti.controllers;

import org.gerbitpcb.supplier.ti.domain.ComponentDto;
import org.gerbitpcb.supplier.ti.domain.ReservationRequest;
import org.gerbitpcb.supplier.ti.domain.ReserveRequest;
import org.gerbitpcb.supplier.ti.domain.ReserveResponse;
import org.gerbitpcb.supplier.ti.services.ComponentService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ComponentController {

    private final ComponentService componentService;

    public ComponentController(ComponentService componentService) {
        this.componentService = componentService;
    }

    @GetMapping("/components")
    public List<ComponentDto> getComponents() {
        return componentService.getAllComponents();
    }

    @PostMapping("/transaction/reserve")
    public ResponseEntity<ReserveResponse> reserve(@RequestBody ReserveRequest request) {
        UUID reservationId = componentService.reserve(request.sku(), request.quantity());
        return ResponseEntity.ok(new ReserveResponse(reservationId));
    }

    @PostMapping("/transaction/commit")
    public ResponseEntity<Void> commit(@RequestBody ReservationRequest request) {
        componentService.commit(request.reservationId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/transaction/rollback")
    public ResponseEntity<Void> rollback(@RequestBody ReservationRequest request) {
        componentService.rollback(request.reservationId());
        return ResponseEntity.noContent().build();
    }
}

