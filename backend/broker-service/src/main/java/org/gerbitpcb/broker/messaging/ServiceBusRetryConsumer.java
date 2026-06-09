package org.gerbitpcb.broker.messaging;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.models.SubQueue;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes the retry queue and the dead-letter
 * sub-queue, delegating each message to {@link OrderRetryHandler}. Only active when
 * {@code app.servicebus.enabled=true} so tests / no-Service-Bus dev runs are unaffected.
 */
@Component
@ConditionalOnProperty(name = "app.servicebus.enabled", havingValue = "true")
public class ServiceBusRetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(ServiceBusRetryConsumer.class);

    private final ServiceBusProcessorClient processor;
    private final ServiceBusProcessorClient deadLetterProcessor;

    public ServiceBusRetryConsumer(@Value("${app.servicebus.connection-string}") String connectionString,
                                   @Value("${app.queue.order-retry}") String queue,
                                   OrderRetryHandler handler) {
        this.processor = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .processor()
                .queueName(queue)
                .maxConcurrentCalls(1)
                .processMessage(ctx -> handler.handle(UUID.fromString(ctx.getMessage().getBody().toString())))
                .processError(ctx -> log.error("Service Bus retry processor error", ctx.getException()))
                .buildProcessorClient();

        this.deadLetterProcessor = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .processor()
                .queueName(queue)
                .subQueue(SubQueue.DEAD_LETTER_QUEUE)
                .maxConcurrentCalls(1)
                .processMessage(ctx -> handler.handleDeadLetter(UUID.fromString(ctx.getMessage().getBody().toString())))
                .processError(ctx -> log.error("Service Bus dead-letter processor error", ctx.getException()))
                .buildProcessorClient();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        processor.start();
        deadLetterProcessor.start();
        log.info("Service Bus retry consumers started");
    }

    @PreDestroy
    public void stop() {
        processor.close();
        deadLetterProcessor.close();
    }
}
