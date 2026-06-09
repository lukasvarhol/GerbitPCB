package org.gerbitpcb.broker.config;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.gerbitpcb.broker.messaging.InMemoryRetryQueue;
import org.gerbitpcb.broker.messaging.RetryQueue;
import org.gerbitpcb.broker.messaging.ServiceBusRetryQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link RetryQueue}: Azure Service Bus when {@code app.servicebus.enabled=true},
 * otherwise an in-memory fallback so the app still boots (and the controller can inject a
 * RetryQueue) in tests / local dev without Service Bus.
 */
@Configuration
public class RetryQueueConfig {

    @Bean
    @ConditionalOnProperty(name = "app.servicebus.enabled", havingValue = "true")
    public ServiceBusSenderClient retrySender(@Value("${app.servicebus.connection-string}") String connectionString,
                                              @Value("${app.queue.order-retry}") String queue) {
        return new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .queueName(queue)
                .buildClient();
    }

    @Bean
    @ConditionalOnProperty(name = "app.servicebus.enabled", havingValue = "true")
    public RetryQueue serviceBusRetryQueue(ServiceBusSenderClient retrySender) {
        return new ServiceBusRetryQueue(retrySender);
    }

    @Bean
    @ConditionalOnMissingBean(RetryQueue.class)
    public InMemoryRetryQueue inMemoryRetryQueue() {
        return new InMemoryRetryQueue();
    }
}
