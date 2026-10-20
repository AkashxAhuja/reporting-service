package com.akash.reporting.messaging;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.akash.reporting.config.DispatchProperties;
import com.akash.reporting.dto.ReportRequestedMessage;
import com.akash.reporting.exception.ReportPublishException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(prefix = "reporting.dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReportQueuePublisher {

    private final RabbitTemplate rabbit;
    private final DispatchProperties properties;
    private final JsonMapper json;

    public ReportQueuePublisher(RabbitTemplate rabbit, DispatchProperties properties, JsonMapper json) {
        this.rabbit = rabbit;
        this.properties = properties;
        this.json = json;
    }

    public void publish(String reportId) {
        var message = MessageBuilder.withBody(json.writeValueAsBytes(new ReportRequestedMessage(reportId)))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).setMessageId(reportId)
                .setType("report.requested.v1").build();
        var correlation = new CorrelationData();
        try {
            rabbit.send(properties.exchange(), properties.routingKey(), message, correlation);
            var confirm = correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new ReportPublishException("RabbitMQ did not acknowledge publication.");
            }
            if (correlation.getReturned() != null) {
                throw new ReportPublishException("RabbitMQ returned the message without routing it to a queue.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ReportPublishException("Interrupted while awaiting RabbitMQ confirmation.", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new ReportPublishException("RabbitMQ publication confirmation was not received in time.", exception);
        } catch (AmqpException exception) {
            throw new ReportPublishException("RabbitMQ publication failed; see application logs.", exception);
        }
    }
}
