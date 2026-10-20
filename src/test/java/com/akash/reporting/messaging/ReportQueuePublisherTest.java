package com.akash.reporting.messaging;

import java.util.UUID;

import com.akash.reporting.config.DispatchProperties;
import com.akash.reporting.exception.ReportPublishException;
import com.akash.reporting.support.DispatchTestSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReportQueuePublisherTest {

    private RabbitTemplate rabbit;
    private DispatchProperties properties;
    private ReportQueuePublisher publisher;

    @BeforeEach
    void setUp() {
        rabbit = mock(RabbitTemplate.class);
        properties = DispatchTestSettings.defaults();
        publisher = new ReportQueuePublisher(rabbit, properties, JsonMapper.builder().build());
    }

    @Test
    void publishesPersistentJsonWithTheReportIdAsMessageId() {
        acknowledge(true, false);
        String id = UUID.randomUUID().toString();

        publisher.publish(id);

        var message = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq(properties.exchange()), eq(properties.routingKey()), message.capture(), any(CorrelationData.class));
        assertThat(JsonMapper.builder().build().readTree(message.getValue().getBody()).get("reportId").asString()).isEqualTo(id);
        assertThat(message.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getValue().getMessageProperties().getMessageId()).isEqualTo(id);
        assertThat(message.getValue().getMessageProperties().getContentType()).isEqualTo("application/json");
    }

    @Test
    void negativeAcknowledgementIsAFailure() {
        acknowledge(false, false);

        assertThatThrownBy(() -> publisher.publish(UUID.randomUUID().toString()))
                .isInstanceOf(ReportPublishException.class).hasMessageContaining("did not acknowledge");
    }

    @Test
    void anAcknowledgedButReturnedMessageIsAFailure() {
        acknowledge(true, true);

        assertThatThrownBy(() -> publisher.publish(UUID.randomUUID().toString()))
                .isInstanceOf(ReportPublishException.class).hasMessageContaining("without routing");
    }

    @Test
    void missingConfirmationTimesOut() {
        assertThatThrownBy(() -> publisher.publish(UUID.randomUUID().toString()))
                .isInstanceOf(ReportPublishException.class).hasMessageContaining("not received in time");
    }

    @Test
    void connectionFailuresAreReportedAsUnconfirmedPublication() {
        doThrow(new AmqpException("connection failed")).when(rabbit)
                .send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.publish(UUID.randomUUID().toString()))
                .isInstanceOf(ReportPublishException.class).hasMessageContaining("publication failed");
    }

    @Test
    void interruptedWaitPreservesTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.publish(UUID.randomUUID().toString()))
                    .isInstanceOf(ReportPublishException.class).hasMessageContaining("Interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private void acknowledge(boolean ack, boolean returned) {
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE",
                        properties.exchange(), properties.routingKey()));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, null));
            return null;
        }).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }
}
