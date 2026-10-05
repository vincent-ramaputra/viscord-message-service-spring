package com.viscord.message_service.messaging;

/**
 * Envelope NestJS's RMQ transport expects: {@code ClientProxy.emit(pattern, data)} sends
 * {@code {"pattern": ..., "data": ...}}, and {@code @MessagePattern}/{@code @EventPattern}
 * handlers are matched on {@code pattern} and receive {@code data}.
 */
public record NestEvent<T>(String pattern, T data) {
}
