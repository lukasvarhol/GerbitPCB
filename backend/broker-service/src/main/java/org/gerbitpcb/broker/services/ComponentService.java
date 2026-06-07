package org.gerbitpcb.broker.services;

import org.gerbitpcb.broker.domain.Component;
import org.gerbitpcb.broker.repository.ComponentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ComponentService {
    private static final Logger log = LoggerFactory.getLogger(ComponentService.class);

    private final ComponentRepository componentRepository; 

    public ComponentService( ComponentRepository componentRepository ) {
	this.componentRepository = componentRepository;
    }

    public List<Component> getAllComponents(){
	return componentRepository.findAll();
    }

    public void updateStock(String sku, String supplier, int availableStock) {
	componentRepository.findBySku(sku).ifPresentOrElse(
							   component -> {
							       component.setAvailableStock(availableStock);
							       componentRepository.save(component);
							   },
							   () -> log.warn("Received stock update for unknown SKU: {}", sku)
							   );
    }
}
