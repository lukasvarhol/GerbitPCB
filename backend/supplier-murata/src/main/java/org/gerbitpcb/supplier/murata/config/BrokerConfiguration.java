package org.gerbitpcb.supplier.murata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "broker")
public class BrokerConfiguration {
    private String webhookUrl;
    public void setWebhookUrl(String webhookUrl) {
	this.webhookUrl = webhookUrl;
    }
    public String getWebhookUrl() {
	return webhookUrl;	
    }
}
