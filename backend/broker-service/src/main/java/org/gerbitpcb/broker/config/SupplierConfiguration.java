package org.gerbitpcb.broker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Externalized Supplier URL Configuration
 * 
 * Allows dynamic supplier endpoint mapping without code changes.
 * Example application.properties:
 *   suppliers.endpoints.TI=http://localhost:8081
 *   suppliers.endpoints.Murata=http://localhost:8082
 * 
 * Or for production (e.g., with remote TI host):
 *   suppliers.endpoints.TI=http://74.248.131.180:8081
 */
@Component
@ConfigurationProperties(prefix = "suppliers")
public class SupplierConfiguration {
    private final Map<String, String> endpoints = new HashMap<>();

    public Map<String, String> getEndpoints() {
        return endpoints;
    }

    public String getSupplierUrl(String supplier) {
        String configuredUrl = endpoints.get(supplier);
        if (configuredUrl == null || configuredUrl.isBlank()) {
            throw new IllegalArgumentException("Supplier endpoint is not configured: " + supplier);
        }
        return configuredUrl;
    }
}
