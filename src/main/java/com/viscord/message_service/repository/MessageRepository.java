package com.viscord.message_service.repository;

import com.viscord.message_service.model.message.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findAllByChannelIdOrderByCreatedAtAsc(UUID channelId);

    long countByChannelId(UUID channelId);

    long countByChannelIdAndCreatedAtAfter(UUID channelId, Instant createdAt);
}
