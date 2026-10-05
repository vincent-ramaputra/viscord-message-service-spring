package com.viscord.message_service.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viscord.message_service.dto.MessageResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class MessageEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private MessageEventPublisher publisher;

    private MessageResponse createResponse() {
        MessageResponse response = new MessageResponse();
        response.setId(UUID.randomUUID());
        response.setChannelId(UUID.randomUUID());
        response.setSenderId(UUID.randomUUID());
        response.setContent("hello");
        response.setCreatedAt(Instant.parse("2026-10-05T13:37:47Z"));
        return response;
    }

    @Test
    @DisplayName("Publishes MESSAGE_CREATED to channel_queue via the default exchange")
    void onMessageCreated_SendsNestEventToChannelQueue() {
        MessageResponse response = createResponse();

        publisher.onMessageCreated(new MessageCreatedEvent(response));

        Mockito.verify(rabbitTemplate).convertAndSend("", Events.CHANNEL_QUEUE, new NestEvent<>(Events.MESSAGE_CREATED, response));
    }

    @Test
    @DisplayName("Broker failure is logged, not rethrown")
    void onMessageCreated_BrokerDown_DoesNotThrow() {
        Mockito.doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).convertAndSend(Mockito.anyString(), Mockito.anyString(), Mockito.any(Object.class));

        Assertions.assertDoesNotThrow(() -> publisher.onMessageCreated(new MessageCreatedEvent(createResponse())));
    }

    @Test
    @DisplayName("Wire format matches what NestJS's RMQ transport expects")
    void nestEvent_SerializesToNestPacket() {
        // Real Boot auto-configuration (Jackson + RabbitTemplate) plus RabbitConfig, so this checks the
        // converter RabbitTemplate actually uses at runtime. No broker connection is made.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, RabbitAutoConfiguration.class))
                .withUserConfiguration(RabbitConfig.class)
                .run(context -> {
                    MessageResponse response = createResponse();
                    Message amqpMessage = context.getBean(RabbitTemplate.class).getMessageConverter()
                            .toMessage(new NestEvent<>(Events.MESSAGE_CREATED, response), new MessageProperties());

                    JsonNode json = context.getBean(ObjectMapper.class).readTree(new String(amqpMessage.getBody(), StandardCharsets.UTF_8));
                    Assertions.assertEquals("message_created", json.get("pattern").asText());
                    Assertions.assertEquals(response.getId().toString(), json.get("data").get("id").asText());
                    Assertions.assertEquals(response.getChannelId().toString(), json.get("data").get("channelId").asText());
                    Assertions.assertEquals(response.getSenderId().toString(), json.get("data").get("senderId").asText());
                    Assertions.assertEquals("2026-10-05T13:37:47Z", json.get("data").get("createdAt").asText());
                    Assertions.assertEquals("application/json", amqpMessage.getMessageProperties().getContentType());
                });
    }
}
