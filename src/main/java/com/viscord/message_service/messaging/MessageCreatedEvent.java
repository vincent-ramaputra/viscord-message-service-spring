package com.viscord.message_service.messaging;

import com.viscord.message_service.dto.MessageResponse;

/**
 * In-process Spring event raised by MessageService once a message is saved.
 * MessageEventPublisher forwards it to RabbitMQ.
 */
public record MessageCreatedEvent(MessageResponse message) {
}
