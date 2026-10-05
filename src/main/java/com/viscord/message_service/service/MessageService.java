package com.viscord.message_service.service;

import com.viscord.message_service.dto.CreateMessageRequest;
import com.viscord.message_service.dto.EditMessageRequest;
import com.viscord.message_service.dto.MessageResponse;
import com.viscord.message_service.enums.StoragePath;
import com.viscord.message_service.exception.BadRequestException;
import com.viscord.message_service.exception.ForbiddenException;
import com.viscord.message_service.exception.NotFoundException;
import com.viscord.message_service.grpc.*;
import com.viscord.message_service.mapper.MessageMapper;
import com.viscord.message_service.messaging.MessageCreatedEvent;
import com.viscord.message_service.model.message.Attachment;
import com.viscord.message_service.model.message.Message;
import com.viscord.message_service.model.message.MessageMention;
import com.viscord.message_service.repository.MessageRepository;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Service
public class MessageService {
    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final StorageService storageService;
    private final ChannelsServiceGrpc.ChannelsServiceBlockingStub channelStub;
    private final ApplicationEventPublisher eventPublisher;

    public MessageService(
            MessageRepository messageRepository,
            MessageMapper messageMapper,
            StorageService storageService,
            @GrpcClient("guild-service") ChannelsServiceGrpc.ChannelsServiceBlockingStub channelStub,
            ApplicationEventPublisher eventPublisher
    ) {
        this.channelStub = channelStub;
        this.eventPublisher = eventPublisher;
        this.messageRepository = messageRepository;
        this.messageMapper = messageMapper;
        this.storageService = storageService;
    }

    public List<MessageResponse> getAllMessages() {
        return messageMapper.toDto(messageRepository.findAll());
    }

    public List<MessageResponse> getChannelMessages(UUID userId, UUID channelId) {
        if (channelId == null) {
            throw new BadRequestException("Invalid channel ID");
        }

        if (userId == null) {
            throw new BadRequestException("Invalid user ID");
        }

        CheckPermissionResponse response = channelStub.checkPermission(
                CheckPermissionRequest.newBuilder()
                        .setChannelId(channelId.toString())
                        .setUserId(userId.toString())
                        .addPermissions(Permission.VIEW_CHANNELS).build()
        );

        if (!response.getAllowed()) {
            throw new ForbiddenException("User is not allowed to perform this action");
        }

        return this.messageMapper.toDto(this.messageRepository.findAllByChannelIdOrderByCreatedAtAsc(channelId));
    }

    public long getUnreadCount(UUID channelId, UUID lastReadId) {
        if (channelId == null) {
            throw new BadRequestException("Invalid channel ID");
        }

        if (lastReadId == null) {
            return this.messageRepository.countByChannelId(channelId);
        }

        Message lastRead = this.messageRepository.findById(lastReadId)
                .orElseThrow(() -> new NotFoundException("Last read message not found"));

        if (!lastRead.getChannelId().equals(channelId)) {
            throw new BadRequestException("Last read message does not belong to this channel");
        }

        return this.messageRepository.countByChannelIdAndCreatedAtAfter(channelId, lastRead.getCreatedAt());
    }

    public MessageResponse createMessage(CreateMessageRequest request) {
        boolean isContentEmpty = request.getContent() == null || request.getContent().isBlank();
        boolean isAttachmentEmpty = request.getAttachments() == null || request.getAttachments().stream().allMatch(file -> file.getSize() == 0);

        if (isContentEmpty && isAttachmentEmpty) {
            throw new BadRequestException("Message content cannot be empty");
        }

        CanUserSendMessageResponse response = channelStub.canUserSendMessage(CanUserSendMessageRequest.newBuilder()
                .setChannelId(request.getChannelId().toString())
                .setUserId(request.getSenderId().toString())
                .build());

        final boolean canUserSendMessage = response.getData();
        if (!canUserSendMessage) {
            if (response.getStatus() == HttpStatus.BAD_REQUEST.value())
                throw new BadRequestException(response.getMessage());
            throw new ForbiddenException(response.getMessage());
        }

        Message message = messageMapper.toEntity(request);
        message = messageRepository.save(message);

        if (!request.getAttachments().isEmpty()) {
            for (MultipartFile file : request.getAttachments()) {
                Attachment att = new Attachment();
                att.setFilename(file.getOriginalFilename());
                att.setSize(file.getSize());
                att.setType(file.getContentType());
                att.setMessage(message);
                att.setMessageId(message.getId());

                String key = storageService.uploadFile(file, StoragePath.ATTACHMENT, message.getId().toString());
                att.setUrl(key);

                message.addAttachment(att);
            }
        }

        if (!request.getMentions().isEmpty()) {
            for (UUID userId : request.getMentions()) {
                MessageMention mention = new MessageMention();
                mention.setMessage(message);
                mention.setMessageId(message.getId());
                mention.setUserId(userId);

                message.addMention(mention);
            }
        }
        message = messageRepository.save(message);

        MessageResponse result = messageMapper.toDto(message);
        eventPublisher.publishEvent(new MessageCreatedEvent(result));
        return result;
    }

    public void deleteMessage(UUID userId, UUID messageId) {
        Message message = messageRepository.findById(messageId).orElseThrow(() -> new NotFoundException("Invalid message ID"));

        CanUserDeleteMessageResponse response = channelStub.canUserDeleteMessage(CanUserDeleteMessageRequest.newBuilder()
                .setUserId(userId.toString())
                .setChannelId(message.getChannelId().toString())
                .setMessageAuthorId(message.getSenderId().toString())
                .build()
        );

        if (!response.getAllowed()) {
            throw new ForbiddenException("User is not allowed to perform this action");
        }
        messageRepository.delete(message);
    }

    public MessageResponse editMessage(EditMessageRequest request) {
        if ((request.getContent() == null || request.getContent().trim().isBlank()) && request.getAttachments() == null) {
            throw new BadRequestException("Content cannot be empty");
        }

        Message message = messageRepository.findById(request.getMessageId()).orElseThrow(() -> new NotFoundException("Invalid message ID"));

        if (!message.getSenderId().equals(request.getUserId())) {
            throw new ForbiddenException("Only the author is allowed to perform this action");
        }

        if (request.getContent() != null) {
            message.setContent(request.getContent().trim());
        }

        if (request.getAttachments() != null) {
            message.getAttachments().removeIf(attachment -> {
                boolean shouldRemove = !request.getAttachments().contains(attachment.getId());
                System.out.println("Should remove: " + shouldRemove);
                if (shouldRemove) {
//                    storageService.deleteFile(attachment.getUrl());
                }
                return shouldRemove;
            });
        }

        messageRepository.save(message);

        return messageMapper.toDto(message);
    }

}
