package com.akash.reporting.messaging;

import java.io.IOException;
import java.util.UUID;
import com.akash.reporting.dto.ReportRequestedMessage;
import com.akash.reporting.service.ReportWorkerService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(prefix = "reporting.worker", name = "enabled", havingValue = "true")
public class ReportJobListener {
    private static final Logger log = LoggerFactory.getLogger(ReportJobListener.class);
    private final JsonMapper json;
    private final ReportWorkerService worker;

    public ReportJobListener(JsonMapper json, ReportWorkerService worker) { this.json = json; this.worker = worker; }

    @RabbitListener(queues = "${reporting.dispatch.queue}", containerFactory = "reportWorkerContainerFactory")
    public void receive(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        String reportId;
        try {
            if (message.getBody().length > 1024) throw new IllegalArgumentException("Oversized job envelope");
            var request = json.readValue(message.getBody(), ReportRequestedMessage.class);
            reportId = UUID.fromString(request.reportId()).toString();
            if (!reportId.equals(request.reportId())) throw new IllegalArgumentException("Invalid job ID");
        } catch (RuntimeException invalid) {
            log.warn("Rejecting invalid report message", invalid);
            channel.basicReject(tag, false);
            return;
        }
        boolean acknowledge;
        try { acknowledge = worker.process(reportId); }
        catch (RuntimeException unavailable) {
            log.warn("Report {} could not confirm its processing outcome", reportId, unavailable);
            acknowledge = false;
        }
        if (acknowledge) channel.basicAck(tag, false);
        else {
            // Basic bounded-rate redelivery while Mongo is unavailable or an earlier lease remains active.
            // Delayed retry queues, retry budgets, and DLQ are milestone 4.
            try { Thread.sleep(1000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            channel.basicNack(tag, false, true);
        }
    }
}
