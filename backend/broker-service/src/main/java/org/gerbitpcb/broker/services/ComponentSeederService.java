package org.gerbitpcb.broker.services;

import org.gerbitpcb.broker.config.SupplierConfiguration;
import org.gerbitpcb.broker.domain.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.gerbitpcb.broker.repository.ComponentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.math.BigDecimal;



@Service
@Profile("!test")
public class ComponentSeederService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ComponentSeederService.class);
    private final ComponentRepository componentRepository;
    private final RestTemplate restTemplate;
    private final SupplierConfiguration supplierConfiguration;

    private record SupplierComponentDto(String sku, String name, BigDecimal price, int availableStock) {};

    public ComponentSeederService(ComponentRepository componentRepository, RestTemplate restTemplate, SupplierConfiguration supplierConfiguration) {
	this.componentRepository = componentRepository;
	this.restTemplate = restTemplate;
	this.supplierConfiguration = supplierConfiguration;
    }

    public void run(ApplicationArguments args) throws Exception {
	for (Map.Entry<String, String> entry : supplierConfiguration.getEndpoints().entrySet()){
	    String url = entry.getValue() + "/api/components";

	    try {
		ResponseEntity<SupplierComponentDto[]> resp = restTemplate.getForEntity(url, SupplierComponentDto[].class);
		SupplierComponentDto[] body = resp.getBody();
		log.info("Seeder got {} components from {}", body == null ? "null" : body.length, url);
		
		if (body == null) continue;
		for (SupplierComponentDto dto : body){
		    Component component = Component.builder().sku(dto.sku()).name(dto.name()).price(dto.price()).supplier(entry.getKey()).availableStock(dto.availableStock()).build();
		    if(componentRepository.findBySku(dto.sku()).isEmpty())
			componentRepository.save(component);
		}
	    } catch (IllegalArgumentException | HttpMessageConversionException | RestClientException ex) {
		log.error("Could not make GET request", ex);
	    }
		
	}
    }
}
