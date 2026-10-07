package com.viscord.message_service.service;

import com.viscord.message_service.config.StorageProperties;
import com.viscord.message_service.dto.*;
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
import io.grpc.Status;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class MessageService {
    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final StorageService storageService;
    private final ChannelsServiceGrpc.ChannelsServiceBlockingStub channelStub;
    private final ApplicationEventPublisher eventPublisher;
    private final StorageProperties storageProperties;

    public MessageService(
            MessageRepository messageRepository,
            MessageMapper messageMapper,
            StorageService storageService,
            @GrpcClient("guild-service") ChannelsServiceGrpc.ChannelsServiceBlockingStub channelStub,
            ApplicationEventPublisher eventPublisher,
            StorageProperties storageProperties
    ) {
        this.channelStub = channelStub;
        this.eventPublisher = eventPublisher;
        this.storageProperties = storageProperties;
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

        CanUserGetChannelMessagesResponse response = channelStub.canUserGetChannelMessages(CanUserGetChannelMessagesRequest.newBuilder()
                .setChannelId(channelId.toString())
                .setUserId(userId.toString())
                .build());

        if (!response.getData()) {
            if (response.getStatus() == HttpStatus.BAD_REQUEST.value())
                throw new BadRequestException(response.getMessage());
            throw new ForbiddenException(response.getMessage());
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

    public void acknowledgeMessage(UUID userId, UUID channelId, UUID messageId) {
        Message message = this.messageRepository.findById(messageId).orElseThrow(() -> new NotFoundException("Message not found"));
        if (!message.getChannelId().equals(channelId)) throw new BadRequestException("Invalid channel id");

        AcknowledgeMessageResponse response = this.channelStub.acknowledgeMessage(AcknowledgeMessageRequest.newBuilder()
                .setUserId(userId.toString())
                .setChannelId(channelId.toString())
                .setMessageId(messageId.toString())
                .build());

        int status = response.getStatus();
        if (status == HttpStatus.NO_CONTENT.value()) return;
        if (status == HttpStatus.BAD_REQUEST.value()) throw new BadRequestException(response.getMessage());
        if (status == HttpStatus.FORBIDDEN.value()) throw new ForbiddenException(response.getMessage());

        // guild-service failed (5xx) or sent a status we don't expect. That isn't the client's fault, so
        // surface it as a downstream failure: GlobalExceptionHandler turns INTERNAL into a 502 and keeps
        // guild-service's message out of the response body.
        throw Status.INTERNAL
                .withDescription("AcknowledgeMessage returned status " + status + ": " + response.getMessage())
                .asRuntimeException();
    }

    public CreateAttachmentResponse createAttachment(CreateAttachmentRequest request) {
        // Validate every file before the gRPC call, so bad input costs no network round trip and
        // the client never gets URLs for only some of its files.
        request.getFiles().forEach(this::validateAttachment);

        CanUserAttachFilesResponse permissionCheckResponse = this.channelStub.canUserAttachFiles(CanUserAttachFilesRequest.newBuilder()
                .setUserId(request.getUserId().toString())
                .setChannelId(request.getChannelId().toString())
                .build());

        int status = permissionCheckResponse.getStatus();
        if (status == HttpStatus.BAD_REQUEST.value()) throw new BadRequestException(permissionCheckResponse.getMessage());
        if (status == HttpStatus.FORBIDDEN.value()) throw new ForbiddenException(permissionCheckResponse.getMessage());
        if (status != HttpStatus.OK.value()) throw Status.INTERNAL.withDescription("CanUserAttachFile returned status" + status +": "+ permissionCheckResponse.getMessage()).asRuntimeException();

        CreateAttachmentResponse response = new CreateAttachmentResponse();
        List<CreateAttachmentResponse.AttachmentUpload> attachments = response.getAttachments();

        for (CreateAttachmentRequest.AttachmentMetadata file : request.getFiles()) {
            StorageService.PresignedUpload presigned = this.storageService.createPutPresignedURL(request.getUserId(), file.fileName(), file.contentType());
            attachments.add(new CreateAttachmentResponse.AttachmentUpload(file.id(), presigned.key(), presigned.url(), presigned.expiresAt()));
        }

        return response;
    }

    /**
     * Rejects files the client declares as too large or of a type we don't accept. These are the
     * client's own claims, so this only stops honest mistakes early: the content type is also signed
     * into the upload URL (S3 rejects a different Content-Type header), and the real size has to be
     * checked with HeadObject when the key is attached to a message.
     */
    private void validateAttachment(CreateAttachmentRequest.AttachmentMetadata file) {
        DataSize maxFileSize = this.storageProperties.maxFileSize();
        if (file.size() > maxFileSize.toBytes()) {
            throw new BadRequestException(file.fileName() + " is larger than the " + maxFileSize.toMegabytes() + "MB limit");
        }

        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(file.contentType());
        } catch (InvalidMediaTypeException e) {
            throw new BadRequestException(file.fileName() + " has an invalid content type");
        }

        // A wildcard like image/* is fine in the allowlist but meaningless as a file's own type,
        // and would be stored as the object's Content-Type.
        if (contentType.isWildcardType() || contentType.isWildcardSubtype()) {
            throw new BadRequestException(file.fileName() + " must have a specific content type");
        }

        boolean allowed = this.storageProperties.allowedContentTypes().stream()
                .anyMatch(allowedType -> allowedType.includes(contentType));
        if (!allowed) {
            throw new BadRequestException(file.fileName() + " has a content type that isn't allowed");
        }
    }
}
