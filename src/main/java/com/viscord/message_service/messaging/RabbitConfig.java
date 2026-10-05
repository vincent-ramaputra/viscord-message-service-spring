package com.viscord.message_service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RabbitConfig {

    /**
     * Publishing to a queue that doesn't exist yet silently drops the message, so declare it here
     * too (RabbitAdmin does this on first connection) instead of relying on guild-service having
     * started first. The arguments must match guild-service's declaration (durable, no extra
     * arguments), otherwise RabbitMQ rejects the redeclaration with PRECONDITION_FAILED.
     */
    @Bean
    Queue channelQueue() {
        return new Queue(Events.CHANNEL_QUEUE, true);
    }

    /**
     * Picked up by the auto-configured RabbitTemplate. Uses Spring's ObjectMapper so the event
     * payload is serialized exactly like the REST response (ISO-8601 dates, same field names).
     */
    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
