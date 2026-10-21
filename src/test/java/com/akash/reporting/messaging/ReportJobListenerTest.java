package com.akash.reporting.messaging;

import java.util.UUID;
import com.akash.reporting.service.ReportWorkerService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportJobListenerTest {
    @Test
    void acknowledgesOnlyAConfirmedProcessingOutcome() throws Exception {
        var worker = mock(ReportWorkerService.class); var channel = mock(Channel.class);
        String id = UUID.randomUUID().toString();
        when(worker.process(id)).thenReturn(true);
        new ReportJobListener(JsonMapper.builder().build(), worker).receive(message("{\"reportId\":\"" + id + "\"}"), channel);
        var order = inOrder(worker, channel);
        order.verify(worker).process(id); order.verify(channel).basicAck(42, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void unconfirmedMetadataWriteIsRequeued() throws Exception {
        var worker = mock(ReportWorkerService.class); var channel = mock(Channel.class);
        when(worker.process(anyString())).thenThrow(new IllegalStateException("Unavailable"));
        new ReportJobListener(JsonMapper.builder().build(), worker).receive(
                message("{\"reportId\":\"" + UUID.randomUUID() + "\"}"), channel);
        verify(channel).basicNack(42, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void poisonMessagesAreRejectedWithoutRunningAJob() throws Exception {
        var worker = mock(ReportWorkerService.class); var channel = mock(Channel.class);
        var listener = new ReportJobListener(JsonMapper.builder().build(), worker);
        listener.receive(message("{\"reportId\":\"bad-id\"}"), channel);
        verify(channel).basicReject(42, false); verifyNoInteractions(worker);
    }

    private Message message(String body) {
        var properties = new MessageProperties(); properties.setDeliveryTag(42);
        return new Message(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
    }
}
