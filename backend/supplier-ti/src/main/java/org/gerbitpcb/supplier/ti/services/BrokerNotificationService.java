package org.gerbitpcb.supplier.ti.services;

import org.gerbitpcb.supplier.ti.config.BrokerConfiguration;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class BrokerNotificationService {

    private static final Logger log = LoggerFactory.getLogger(BrokerNotificationService.class);
    public record StockUpdateRequest(String sku, String supplier, int availableStock) {}
    
    private final RestTemplate restTemplate;
    private final BrokerConfiguration brokerConfiguration;
    
    public BrokerNotificationService(RestTemplate restTemplate, BrokerConfiguration brokerConfiguration){
	this.restTemplate = restTemplate;
	this.brokerConfiguration = brokerConfiguration;
    }

    void notifyStockUpdate(String sku, int availableStock) {
	String base = brokerConfiguration.getWebhookUrl();
	String url = base + "/api/components/stock-update";

	try {
	    StockUpdateRequest body = new StockUpdateRequest(sku, "TI", availableStock);

	    restTemplate.postForEntity(url, body, Void.class);
	} catch (Exception ex) {
	    log.error("Failed to make request",ex);
	}
    }
}
