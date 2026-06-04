package org.gerbitpcb.broker.controllers;

import org.gerbitpcb.broker.domain.Component;
import org.gerbitpcb.broker.dto.StockUpdateRequest;
import org.gerbitpcb.broker.services.ComponentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/components")
public class ComponentsController {
    private final ComponentService componentService;

    public ComponentsController(ComponentService componentService) {
	this.componentService = componentService;
    }

    @GetMapping
    public ResponseEntity<List<Component>> getAllComponents() {
	List<Component> components = componentService.getAllComponents();
	return ResponseEntity.ok(components);
    }

    @PostMapping("/stock-update")
    public ResponseEntity<Void> updateStock(@Valid @RequestBody StockUpdateRequest request){
	componentService.updateStock(request.sku(), request.supplier(), request.availableStock());
	return ResponseEntity.ok().build(); 
    }
}
