package com.viscord.message_service.service;

import com.viscord.message_service.config.StorageProperties;
import com.viscord.message_service.dto.*;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class MessageService {
    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final StorageService storageService;
    private final ChannelsServiceGrpc.ChannelsServiceBlockingStub channelStub;
    private final ApplicationEventPublisher eventPublisher;
    private final StorageProperties storageProperties;
    private final TransactionTemplate transactionTemplate;

    public MessageService(
            MessageRepository messageRepository,
            MessageMapper messageMapper,
            StorageService storageService,
            @GrpcClient("guild-service") ChannelsServiceGrpc.ChannelsServiceBlockingStub channelStub,
            ApplicationEventPublisher eventPublisher,
            StorageProperties storageProperties,
            TransactionTemplate transactionTemplate
    ) {
        this.transactionTemplate = transactionTemplate;
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

    /**
     * Creates a message whose attachments were already uploaded through presigned URLs.
     * <p>
     * S3 and Postgres can't share a transaction, so the order decides what a failure leaves behind:
     * every object is checked and copied to its permanent key first, the rows are written in one
     * short transaction, and only after the commit are the pending originals deleted. If the
     * transaction fails, the copies are removed and the originals stay, so the client can retry.
     * If a delete fails, the object is orphaned, which costs storage but never shows a user a
     * broken attachment.
     * <p>
     * Accepted race: two concurrent requests with the same pending key can both pass the
     * findObject check before either deletes the original, so both messages get a copy. The
     * sender can only attach their own uploads, so the effect is a duplicate of their own file.
     */
    public MessageResponse createMessage(CreateMessageRequest request) {
        List<CreateMessageRequest.AttachmentKey> attachmentKeys = request.getAttachments() == null ? List.of() : request.getAttachments();
        boolean isContentEmpty = request.getContent() == null || request.getContent().isBlank();

        if (isContentEmpty && attachmentKeys.isEmpty()) {
            throw new BadRequestException("Message content cannot be empty");
        }

        // Ownership and duplicate checks need no network call, so they run before the gRPC checks.
        validateAttachmentKeys(request.getSenderId(), attachmentKeys);

        checkCanSendMessage(request.getSenderId(), request.getChannelId());
        if (!attachmentKeys.isEmpty()) {
            checkCanAttachFiles(request.getSenderId(), request.getChannelId());
        }

        List<Attachment> attachments = copyAttachments(request.getChannelId(), attachmentKeys);

        Message message;
        try {
            message = this.transactionTemplate.execute(status -> saveMessage(request, attachments));
        } catch (RuntimeException e) {
            attachments.forEach(attachment -> this.storageService.deleteFileQuietly(attachment.getUrl()));
            throw e;
        }

        attachmentKeys.forEach(attachmentKey -> this.storageService.deleteFileQuietly(attachmentKey.key()));

        MessageResponse result = messageMapper.toDto(message);
        eventPublisher.publishEvent(new MessageCreatedEvent(result));
        return result;
    }

    private void validateAttachmentKeys(UUID senderId, List<CreateMessageRequest.AttachmentKey> attachmentKeys) {
        Set<String> seen = new HashSet<>();
        for (CreateMessageRequest.AttachmentKey attachmentKey : attachmentKeys) {
            if (!this.storageService.isPendingKeyOwnedBy(attachmentKey.key(), senderId)) {
                throw new BadRequestException(attachmentKey.fileName() + " is not an upload of yours");
            }
            if (!seen.add(attachmentKey.key())) {
                throw new BadRequestException(attachmentKey.fileName() + " is attached more than once");
            }
        }
    }

    /**
     * Checks what S3 actually holds for every key before copying any of them, so one bad file
     * doesn't leave copies of the others behind. Size and type come from S3, not from the
     * client's earlier claims.
     */
    private List<Attachment> copyAttachments(UUID channelId, List<CreateMessageRequest.AttachmentKey> attachmentKeys) {
        List<StorageService.StoredObject> storedObjects = new ArrayList<>();
        for (CreateMessageRequest.AttachmentKey attachmentKey : attachmentKeys) {
            StorageService.StoredObject stored = this.storageService.findObject(attachmentKey.key())
                    .orElseThrow(() -> new BadRequestException(attachmentKey.fileName() + " was not uploaded, or its upload has expired"));
            checkFileSize(attachmentKey.fileName(), stored.size());
            checkContentType(attachmentKey.fileName(), stored.contentType());
            storedObjects.add(stored);
        }

        List<Attachment> attachments = new ArrayList<>();
        try {
            for (int i = 0; i < attachmentKeys.size(); i++) {
                CreateMessageRequest.AttachmentKey attachmentKey = attachmentKeys.get(i);
                StorageService.StoredObject stored = storedObjects.get(i);

                Attachment attachment = new Attachment();
                attachment.setFilename(attachmentKey.fileName());
                attachment.setSize(stored.size());
                attachment.setType(stored.contentType());
                attachment.setUrl(this.storageService.copyToAttachments(attachmentKey.key(), channelId));
                attachments.add(attachment);
            }
        } catch (RuntimeException e) {
            attachments.forEach(attachment -> this.storageService.deleteFileQuietly(attachment.getUrl()));
            throw e;
        }
        return attachments;
    }

    /** Runs inside the transaction: no remote calls here, so a DB connection is held only briefly. */
    private Message saveMessage(CreateMessageRequest request, List<Attachment> attachments) {
        Message message = messageRepository.save(messageMapper.toEntity(request));

        for (Attachment attachment : attachments) {
            attachment.setMessage(message);
            attachment.setMessageId(message.getId());
            message.addAttachment(attachment);
        }
        addMentions(message, request.getMentions());

        return messageRepository.save(message);
    }

    private void addMentions(Message message, List<UUID> mentions) {
        if (mentions == null) return;

        for (UUID userId : mentions) {
            MessageMention mention = new MessageMention();
            mention.setMessage(message);
            mention.setMessageId(message.getId());
            mention.setUserId(userId);

            message.addMention(mention);
        }
    }

    private void checkCanSendMessage(UUID userId, UUID channelId) {
        CanUserSendMessageResponse response = channelStub.canUserSendMessage(CanUserSendMessageRequest.newBuilder()
                .setChannelId(channelId.toString())
                .setUserId(userId.toString())
                .build());

        if (!response.getData()) {
            if (response.getStatus() == HttpStatus.BAD_REQUEST.value())
                throw new BadRequestException(response.getMessage());
            throw new ForbiddenException(response.getMessage());
        }
    }

    private void checkCanAttachFiles(UUID userId, UUID channelId) {
        CanUserAttachFilesResponse response = this.channelStub.canUserAttachFiles(CanUserAttachFilesRequest.newBuilder()
                .setUserId(userId.toString())
                .setChannelId(channelId.toString())
                .build());

        int status = response.getStatus();
        if (status == HttpStatus.BAD_REQUEST.value())
            throw new BadRequestException(response.getMessage());
        if (status == HttpStatus.FORBIDDEN.value())
            throw new ForbiddenException(response.getMessage());
        if (status != HttpStatus.OK.value())
            throw Status.INTERNAL.withDescription("CanUserAttachFiles returned status " + status + ": " + response.getMessage()).asRuntimeException();
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

        checkCanAttachFiles(request.getUserId(), request.getChannelId());

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
        checkFileSize(file.fileName(), file.size());
        checkContentType(file.fileName(), file.contentType());
    }

    private void checkFileSize(String fileName, long size) {
        DataSize maxFileSize = this.storageProperties.maxFileSize();
        if (size > maxFileSize.toBytes()) {
            throw new BadRequestException(fileName + " is larger than the " + maxFileSize.toMegabytes() + "MB limit");
        }
    }

    private void checkContentType(String fileName, String rawContentType) {
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(rawContentType);
        } catch (InvalidMediaTypeException e) {
            throw new BadRequestException(fileName + " has an invalid content type");
        }

        // A wildcard like image/* is fine in the allowlist but meaningless as a file's own type,
        // and would be stored as the object's Content-Type.
        if (contentType.isWildcardType() || contentType.isWildcardSubtype()) {
            throw new BadRequestException(fileName + " must have a specific content type");
        }

        boolean allowed = this.storageProperties.allowedContentTypes().stream()
                .anyMatch(allowedType -> allowedType.includes(contentType));
        if (!allowed) {
            throw new BadRequestException(fileName + " has a content type that isn't allowed");
        }
    }
}
