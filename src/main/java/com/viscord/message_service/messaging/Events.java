package com.viscord.message_service.messaging;

/**
 * RabbitMQ queue and pattern names shared with the NestJS services
 * (mirrors src/constants/events.ts there; keep the values in sync).
 */
public final class Events {
    public static final String CHANNEL_QUEUE = "channel_queue";

    public static final String MESSAGE_CREATED = "message_created";

    private Events() {
    }
}
