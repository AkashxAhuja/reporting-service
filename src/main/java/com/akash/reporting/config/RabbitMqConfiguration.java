package com.akash.reporting.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "reporting.dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfiguration {

    @Bean
    DirectExchange reportExchange(DispatchProperties properties) {
        return new DirectExchange(properties.exchange(), true, false);
    }

    @Bean
    Queue reportQueue(DispatchProperties properties) {
        return QueueBuilder.durable(properties.queue()).build();
    }

    @Bean
    Binding reportBinding(Queue reportQueue, DirectExchange reportExchange, DispatchProperties properties) {
        return BindingBuilder.bind(reportQueue).to(reportExchange).with(properties.routingKey());
    }

    @Bean
    RabbitTemplate reportRabbitTemplate(CachingConnectionFactory connectionFactory) {
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        var template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        return template;
    }
}
