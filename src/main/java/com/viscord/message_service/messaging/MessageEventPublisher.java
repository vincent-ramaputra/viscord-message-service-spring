package com.viscord.message_service.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Sends MESSAGE_CREATED to guild-service, which updates unread counts and lastMessageId and
     * fans the message out to recipients through ws-gateway.
     * <p>
     * AFTER_COMMIT means consumers never see a message that was rolled back. createMessage has no
     * transaction today, so fallbackExecution runs the listener right away; once @Transactional is
     * added, this keeps working without changes.
     * <p>
     * A broker failure is logged, not rethrown: the message is already saved and returned to the
     * sender, so failing the request would only make the client retry and create a duplicate.
     * The cost is that recipients miss the realtime update until they refetch. A transactional
     * outbox would close that gap.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessageCreated(MessageCreatedEvent event) {
        try {
            rabbitTemplate.convertAndSend("", Events.CHANNEL_QUEUE, new NestEvent<>(Events.MESSAGE_CREATED, event.message()));
        } catch (AmqpException e) {
            log.error("Failed to publish {} for message {}", Events.MESSAGE_CREATED, event.message().getId(), e);
        }
    }
}
