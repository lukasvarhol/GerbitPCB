package org.gerbitpcb.broker.messaging;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Production {@link RetryQueue} backed by Azure Service Bus. The message body is the
 * transaction id; {@link #scheduleEnqueue} uses a Service Bus scheduled message so the
 * backoff delay is handled by the broker, not a busy-waiting consumer.
 */
public class ServiceBusRetryQueue implements RetryQueue {

    private final ServiceBusSenderClient sender;

    public ServiceBusRetryQueue(ServiceBusSenderClient sender) {
        this.sender = sender;
    }

    @Override
    public void enqueue(UUID transactionId) {
        sender.sendMessage(new ServiceBusMessage(transactionId.toString()));
    }

    @Override
    public void scheduleEnqueue(UUID transactionId, Duration delay) {
        sender.scheduleMessage(new ServiceBusMessage(transactionId.toString()),
                OffsetDateTime.now().plus(delay));
    }
}
