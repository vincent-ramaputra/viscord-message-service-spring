package com.viscord.message_service.service;

import com.viscord.message_service.config.StorageProperties;
import com.viscord.message_service.dto.CreateAttachmentRequest;
import com.viscord.message_service.dto.CreateAttachmentResponse;
import com.viscord.message_service.dto.CreateMessageRequest;
import com.viscord.message_service.dto.EditMessageRequest;
import com.viscord.message_service.dto.MessageResponse;
import com.viscord.message_service.exception.BadRequestException;
import com.viscord.message_service.exception.ForbiddenException;
import com.viscord.message_service.exception.NotFoundException;
import com.viscord.message_service.grpc.AcknowledgeMessageRequest;
import com.viscord.message_service.grpc.AcknowledgeMessageResponse;
import com.viscord.message_service.grpc.CanUserAttachFilesRequest;
import com.viscord.message_service.grpc.CanUserAttachFilesResponse;
import com.viscord.message_service.grpc.CanUserDeleteMessageResponse;
import com.viscord.message_service.grpc.CanUserSendMessageResponse;
import com.viscord.message_service.grpc.ChannelsServiceGrpc;
import com.viscord.message_service.grpc.CanUserGetChannelMessagesResponse;
import com.viscord.message_service.mapper.AttachmentMapper;
import com.viscord.message_service.mapper.MessageMapper;
import com.viscord.message_service.mapper.StorageUrlMapper;
import com.viscord.message_service.messaging.MessageCreatedEvent;
import com.viscord.message_service.model.message.Attachment;
import com.viscord.message_service.model.message.Message;
import com.viscord.message_service.repository.MessageRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
public class MessageServiceTest {
    @Spy
    private MessageMapper messageMapper = Mappers.getMapper(MessageMapper.class);

    @Spy
    private AttachmentMapper attachmentMapper = Mappers.getMapper(AttachmentMapper.class);

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ChannelsServiceGrpc.ChannelsServiceBlockingStub channelStub;

    @Mock
    private StorageService storageService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private MessageService messageService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(messageMapper, "attachmentMapper", attachmentMapper);
        ReflectionTestUtils.setField(attachmentMapper, "storageUrlMapper", new StorageUrlMapper(
                new StorageProperties(Duration.ofMinutes(5), URI.create(CDN_ENDPOINT), null, MAX_FILE_SIZE, List.of(MediaType.ALL))));
        // No real transaction manager in a unit test: run the callback directly. Lenient because
        // most tests never reach the transaction.
        Mockito.lenient().when(transactionTemplate.execute(Mockito.any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        // The ownership rule is a pure string check, so tests use the real one.
        Mockito.lenient().when(storageService.isPendingKeyOwnedBy(Mockito.any(), Mockito.any())).thenCallRealMethod();
    }

    private CreateMessageRequest createRequest(String content) {
        CreateMessageRequest req = new CreateMessageRequest();
        req.setContent(content);
        req.setChannelId(UUID.randomUUID());
        req.setSenderId(UUID.randomUUID());

        return req;
    }

    private CanUserSendMessageResponse createCanUserSendMessageResponse(boolean allowed, int status, String msg) {
        return CanUserSendMessageResponse.newBuilder()
                .setData(allowed)
                .setMessage(msg)
                .setStatus(status)
                .build();
    }

    private CanUserGetChannelMessagesResponse createCanUserGetChannelMessagesResponse(boolean allowed, int status, String msg) {
        return CanUserGetChannelMessagesResponse.newBuilder()
                .setData(allowed)
                .setMessage(msg)
                .setStatus(status)
                .build();
    }

    private Message createMessage(String content) {
        Message message = new Message();
        message.setContent(content);
        message.setId(UUID.randomUUID());
        message.setSenderId(UUID.randomUUID());
        message.setChannelId(UUID.randomUUID());

        return message;
    }

    private Attachment createAttachment(Message message) {
        Attachment attachment = new Attachment();
        attachment.setId(UUID.randomUUID());
        attachment.setMessageId(message.getId());
        attachment.setFilename("attachment.txt");
        attachment.setSize(512);

        return attachment;
    }

    @Test
    @DisplayName("Unhpapy path: given unauthorized user, should return forbidden error")
    void getChannelMessages_UnauthorizedUser_ShouldReturnForbidden() {
        UUID userId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();

        Message message = this.createMessage("test123");
        message.addAttachment(this.createAttachment(message));

        List<Message> messages = List.of(message);

        Mockito.when(channelStub.canUserGetChannelMessages(Mockito.any())).thenReturn(
                createCanUserGetChannelMessagesResponse(false, HttpStatus.FORBIDDEN.value(), "User is not a recipient of this channel"));

        Assertions.assertThrows(ForbiddenException.class, () -> {
            List<MessageResponse> result = messageService.getChannelMessages(userId, channelId);
        });

        Mockito.verify(this.messageRepository, Mockito.never()).findAllByChannelIdOrderByCreatedAtAsc(Mockito.any());
    }

    @Test
    @DisplayName("Unhappy path: given invalid channel or user ID, should throw bad request error")
    void getChannelMessages_InvalidChannel_ThrowsBadRequest() {
        UUID userId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();

        Mockito.when(channelStub.canUserGetChannelMessages(Mockito.any())).thenReturn(
                createCanUserGetChannelMessagesResponse(false, HttpStatus.BAD_REQUEST.value(), "Invalid channel or user ID"));

        Assertions.assertThrows(BadRequestException.class, () -> {
            messageService.getChannelMessages(userId, channelId);
        });

        Mockito.verify(this.messageRepository, Mockito.never()).findAllByChannelIdOrderByCreatedAtAsc(Mockito.any());
    }

    @Test
    @DisplayName("Happy path: given valid request, should return channel messages")
    void getChannelMessages_ValidRequest_ShouldReturnChannelMessages() {
        UUID userId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();

        Message message = this.createMessage("test123");
        message.addAttachment(this.createAttachment(message));

        List<Message> messages = List.of(message);

        Mockito.when(messageRepository.findAllByChannelIdOrderByCreatedAtAsc(channelId)).thenReturn(messages);
        Mockito.when(channelStub.canUserGetChannelMessages(Mockito.any())).thenReturn(
                createCanUserGetChannelMessagesResponse(true, HttpStatus.OK.value(), ""));

        List<MessageResponse> result = messageService.getChannelMessages(userId, channelId);

        Mockito.verify(messageMapper).toDto(messages);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals(1, result.get(0).getAttachments().size());
    }

    @Test
    @DisplayName("Unhappy path: given empty content, should throw bad request error")
    void createMessage_EmptyRequest_ThrowsBadRequest() {
        CreateMessageRequest req = createRequest("");

        Assertions.assertThrows(BadRequestException.class, () -> {
            messageService.createMessage(req);
        });

        Mockito.verify(channelStub, Mockito.never()).canUserSendMessage(Mockito.any());
    }

    @Test
    @DisplayName("Unhappy path: when user is not allowed, should throw forbidden error")
    void createMessage_UserNotAllowed_ThrowsForbidden() {
        CreateMessageRequest req = createRequest("This is a test message");

        Mockito.when(channelStub.canUserSendMessage(Mockito.any()))
                .thenReturn(createCanUserSendMessageResponse(false, HttpStatus.FORBIDDEN.value(), "User is not allowed"));

        Assertions.assertThrows(ForbiddenException.class, () -> {
            messageService.createMessage(req);
        });

        Mockito.verify(messageRepository, Mockito.never()).save(Mockito.any());
        Mockito.verify(eventPublisher, Mockito.never()).publishEvent(Mockito.any(Object.class));
    }

    @Test
    @DisplayName("Unhappy path: when user/channel id is invalid, should throw bad request error")
    void createMessage_InvalidRequest_ThrowsBadRequest() {
        CreateMessageRequest req = createRequest("This is a test message");

        Mockito.when(channelStub.canUserSendMessage(Mockito.any()))
                .thenReturn(createCanUserSendMessageResponse(false, HttpStatus.BAD_REQUEST.value(), "Invalid channel or user ID"));

        Assertions.assertThrows(BadRequestException.class, () -> {
            messageService.createMessage(req);
        });

        Mockito.verify(messageRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    @DisplayName("Happy path: given valid request, should save message")
    void createMessage_ValidRequest_ReturnsMessageResponse() {
        CreateMessageRequest req = createRequest("Test message");

        MessageResponse expectedResponse = new MessageResponse();

        expectedResponse.setChannelId(req.getChannelId());
        expectedResponse.setContent(req.getContent());
        expectedResponse.setSenderId(req.getSenderId());

        Mockito.when(channelStub.canUserSendMessage(Mockito.any())).thenReturn(createCanUserSendMessageResponse(true, HttpStatus.OK.value(), ""));
        Mockito.when(messageRepository.save(ArgumentMatchers.any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MessageResponse result = messageService.createMessage(req);

        Mockito.verify(messageMapper).toEntity(req);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(req.getSenderId(), result.getSenderId());
        Assertions.assertEquals(req.getContent(), result.getContent());
        Mockito.verify(eventPublisher).publishEvent(new MessageCreatedEvent(result));
    }

    @Test
    @DisplayName("Happy path: given valid request, should delete message")
    void deleteMessage_ValidRequest_ReturnsEmpty() {
        Message message = createMessage("test");

        Mockito.when(messageRepository.findById(message.getId())).thenReturn(Optional.of(message));
        Mockito.when(channelStub.canUserDeleteMessage(Mockito.any())).thenReturn(
                CanUserDeleteMessageResponse.newBuilder().setAllowed(true).build());

        messageService.deleteMessage(message.getSenderId(), message.getId());

        Mockito.verify(messageRepository, Mockito.times(1)).delete(message);
    }

    @Test
    @DisplayName("Unhappy path: given invalid message id, should throw not found error")
    void deleteMessage_InvalidMessageId_ThrowsNotFound() {
        Mockito.when(messageRepository.findById(Mockito.any())).thenReturn(Optional.empty());

        Assertions.assertThrows(NotFoundException.class, () -> {
            messageService.deleteMessage(UUID.randomUUID(), UUID.randomUUID());
        });

        Mockito.verify(messageRepository, Mockito.never()).delete(Mockito.any());
        Mockito.verify(channelStub, Mockito.never()).canUserDeleteMessage(Mockito.any());
    }

    @Test
    @DisplayName("Unhappy path: when delete message permission is denied, should throw forbidden")
    void deleteMessage_PermissionDenied_ThrowsForbidden() {
        Message message = createMessage("test");

        Mockito.when(messageRepository.findById(message.getId())).thenReturn(Optional.of(message));
        Mockito.when(channelStub.canUserDeleteMessage(Mockito.any())).thenReturn(CanUserDeleteMessageResponse.newBuilder().setAllowed(false).build());

        Assertions.assertThrows(ForbiddenException.class, () -> {
            messageService.deleteMessage(message.getSenderId(), message.getId());
        });

        Mockito.verify(messageRepository, Mockito.never()).delete(Mockito.any());
    }

    @Test
    @DisplayName("Happy path: when changing content, should return edited message and not modify attachment")
    void editMessage_ValidRequest_ReturnsEditedMessage() {
        Message message = createMessage("Old message");
        message.addAttachment(createAttachment(message));

        EditMessageRequest request = new EditMessageRequest();
        request.setMessageId(message.getId());
        request.setContent("   New message");
        request.setUserId(message.getSenderId());

        Mockito.when(messageRepository.findById(request.getMessageId())).thenReturn(Optional.of(message));

        MessageResponse response = messageService.editMessage(request);

        Assertions.assertEquals(request.getContent().trim(), response.getContent());
        Mockito.verify(messageRepository).save(message);
        Mockito.verify(storageService, Mockito.never()).deleteFile(Mockito.any());
        Assertions.assertEquals(1, response.getAttachments().size());
    }

    @Test
    @DisplayName("Happy path: should remove attachments not in request, and return edited message")
    void editMessage_RemoveAttachmentsNotInRequest_ReturnsEdit() {
        Message message = createMessage("");
        message.addAttachment(createAttachment(message));

        EditMessageRequest request = new EditMessageRequest();
        request.setMessageId(message.getId());
        request.setUserId(message.getSenderId());
        request.setAttachments(List.of(UUID.randomUUID()));

        Mockito.when(messageRepository.findById(request.getMessageId())).thenReturn(Optional.of(message));

        MessageResponse response = messageService.editMessage(request);

        Assertions.assertEquals(0, response.getAttachments().size());
    }

    @Test
    @DisplayName("Unhappy path: given empty content, should throws bad request")
    void editMessage_EmptyContent_ThrowsBadRequest() {
        EditMessageRequest request = new EditMessageRequest();
        request.setMessageId(UUID.randomUUID());
        request.setUserId(UUID.randomUUID());

        Assertions.assertThrows(BadRequestException.class, () -> {
            messageService.editMessage(request);
        });

        Mockito.verify(messageRepository, Mockito.never()).findById(Mockito.any());
    }

    @Test
    @DisplayName("Happy path: given no last read message, should count every message in the channel")
    void getUnreadCount_NoLastRead_CountsAllMessages() {
        UUID channelId = UUID.randomUUID();

        Mockito.when(messageRepository.countByChannelId(channelId)).thenReturn(7L);

        long count = messageService.getUnreadCount(channelId, null);

        Assertions.assertEquals(7L, count);
        Mockito.verify(messageRepository, Mockito.never()).findById(Mockito.any());
    }

    @Test
    @DisplayName("Happy path: given last read message, should count messages created after it")
    void getUnreadCount_WithLastRead_CountsMessagesAfterIt() {
        Message lastRead = this.createMessage("read");
        lastRead.setCreatedAt(Instant.parse("2026-09-01T10:00:00Z"));

        Mockito.when(messageRepository.findById(lastRead.getId())).thenReturn(Optional.of(lastRead));
        Mockito.when(messageRepository.countByChannelIdAndCreatedAtAfter(lastRead.getChannelId(), lastRead.getCreatedAt()))
                .thenReturn(3L);

        long count = messageService.getUnreadCount(lastRead.getChannelId(), lastRead.getId());

        Assertions.assertEquals(3L, count);
    }

    @Test
    @DisplayName("Unhappy path: given unknown last read message, should throw not found")
    void getUnreadCount_UnknownLastRead_ThrowsNotFound() {
        UUID lastReadId = UUID.randomUUID();

        Mockito.when(messageRepository.findById(lastReadId)).thenReturn(Optional.empty());

        Assertions.assertThrows(NotFoundException.class, () -> {
            messageService.getUnreadCount(UUID.randomUUID(), lastReadId);
        });
    }

    @Test
    @DisplayName("Unhappy path: given last read message from another channel, should throw bad request")
    void getUnreadCount_LastReadFromOtherChannel_ThrowsBadRequest() {
        Message lastRead = this.createMessage("other channel");

        Mockito.when(messageRepository.findById(lastRead.getId())).thenReturn(Optional.of(lastRead));

        Assertions.assertThrows(BadRequestException.class, () -> {
            messageService.getUnreadCount(UUID.randomUUID(), lastRead.getId());
        });

        Mockito.verify(messageRepository, Mockito.never()).countByChannelIdAndCreatedAtAfter(Mockito.any(), Mockito.any());
    }

    private AcknowledgeMessageResponse createAcknowledgeMessageResponse(int status, String msg) {
        return AcknowledgeMessageResponse.newBuilder()
                .setStatus(status)
                .setMessage(msg)
                .build();
    }

    @Test
    @DisplayName("Happy path: given valid request, should forward the acknowledgement to guild-service")
    void acknowledgeMessage_ValidRequest_SendsGrpcRequest() {
        Message message = this.createMessage("test");
        UUID userId = UUID.randomUUID();

        Mockito.when(messageRepository.findById(message.getId())).thenReturn(Optional.of(message));
        Mockito.when(channelStub.acknowledgeMessage(Mockito.any()))
                .thenReturn(createAcknowledgeMessageResponse(HttpStatus.NO_CONTENT.value(), ""));

        messageService.acknowledgeMessage(userId, message.getChannelId(), message.getId());

        ArgumentCaptor<AcknowledgeMessageRequest> captor = ArgumentCaptor.forClass(AcknowledgeMessageRequest.class);
        Mockito.verify(channelStub).acknowledgeMessage(captor.capture());
        Assertions.assertEquals(userId.toString(), captor.getValue().getUserId());
        Assertions.assertEquals(message.getChannelId().toString(), captor.getValue().getChannelId());
        Assertions.assertEquals(message.getId().toString(), captor.getValue().getMessageId());

        Mockito.verify(messageRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("Unhappy path: given unknown message id, should throw not found")
    void acknowledgeMessage_UnknownMessage_ThrowsNotFound() {
        Mockito.when(messageRepository.findById(Mockito.any())).thenReturn(Optional.empty());

        Assertions.assertThrows(NotFoundException.class, () -> {
            messageService.acknowledgeMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        });

        Mockito.verify(channelStub, Mockito.never()).acknowledgeMessage(Mockito.any());
    }

    @Test
    @DisplayName("Unhappy path: given message from another channel, should throw bad request")
    void acknowledgeMessage_MessageFromOtherChannel_ThrowsBadRequest() {
        Message message = this.createMessage("other channel");

        Mockito.when(messageRepository.findById(message.getId())).thenReturn(Optional.of(message));

        Assertions.assertThrows(BadRequestException.class, () -> {
            messageService.acknowledgeMessage(UUID.randomUUID(), UUID.randomUUID(), message.getId());
        });

        Mockito.verify(channelStub, Mockito.never()).acknowledgeMessage(Mockito.any());
    }

    @Test
    @DisplayName("Unhappy path: when guild-service rejects the request, should throw bad request with its message")
    void acknowledgeMessage_GuildServiceBadRequest_ThrowsBadRequest() {
        Message message = this.createMessage("test");

        Mockito.when(messageRepository.findById(message.getId())).thenReturn(Optional.of(message));
        Mockito.when(channelStub.acknowledgeMessage(Mockito.any()))
                .thenReturn(createAcknowledgeMessageResponse(HttpStatus.BAD_REQUEST.value(), "Channel does not exist"));

        BadRequestException e = Assertions.assertThrows(BadRequestException.class, () -> {
            messageService.acknowledgeMessage(UUID.randomUUID(), message.getChannelId(), message.getId());
        });

        Assertions.assertEquals("Channel does not exist", e.getMessage());
    }

    @Test
    @DisplayName("Unhappy path: when user cannot view the channel, should throw forbidden")
    void acknowledgeMessage_PermissionDenied_ThrowsForbidden() {
        Message message = this.createMessage("test");

        Mockito.when(messageRepository.findById(message.getId())).thenReturn(Optional.of(message));
        Mockito.when(channelStub.acknowledgeMessage(Mockito.any()))
                .thenReturn(createAcknowledgeMessageResponse(HttpStatus.FORBIDDEN.value(), "User does not have permission to read message"));

        ForbiddenException e = Assertions.assertThrows(ForbiddenException.class, () -> {
            messageService.acknowledgeMessage(UUID.randomUUID(), message.getChannelId(), message.getId());
        });

        Assertions.assertEquals("User does not have permission to read message", e.getMessage());
    }

    // 0 is what an unset proto3 int32 reads as; 200 is a success code, but not the one guild-service promises.
    @ParameterizedTest
    @ValueSource(ints = {0, 200, 500})
    @DisplayName("Unhappy path: when guild-service fails or returns an unexpected status, should throw an INTERNAL gRPC error")
    void acknowledgeMessage_UnexpectedStatus_ThrowsInternal(int status) {
        Message message = this.createMessage("test");

        Mockito.when(messageRepository.findById(message.getId())).thenReturn(Optional.of(message));
        Mockito.when(channelStub.acknowledgeMessage(Mockito.any()))
                .thenReturn(createAcknowledgeMessageResponse(status, "boom"));

        StatusRuntimeException e = Assertions.assertThrows(StatusRuntimeException.class, () -> {
            messageService.acknowledgeMessage(UUID.randomUUID(), message.getChannelId(), message.getId());
        });

        Assertions.assertEquals(Status.Code.INTERNAL, e.getStatus().getCode());
    }

    @Test
    @DisplayName("Unhappy path: when guild-service is unreachable, should propagate the gRPC error unchanged")
    void acknowledgeMessage_GuildServiceUnavailable_PropagatesGrpcError() {
        Message message = this.createMessage("test");

        Mockito.when(messageRepository.findById(message.getId())).thenReturn(Optional.of(message));
        Mockito.when(channelStub.acknowledgeMessage(Mockito.any()))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());

        StatusRuntimeException e = Assertions.assertThrows(StatusRuntimeException.class, () -> {
            messageService.acknowledgeMessage(UUID.randomUUID(), message.getChannelId(), message.getId());
        });

        Assertions.assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
    }

    private static final DataSize MAX_FILE_SIZE = DataSize.ofMegabytes(25);
    private static final String CDN_ENDPOINT = "https://cdn.test";

    /**
     * The @InjectMocks instance gets null StorageProperties, so attachment tests build their own
     * service with explicit limits. Production allows every content type (*\/*), so tests that need
     * a rejected type pass a narrower list.
     */
    private MessageService attachmentService(MediaType... allowedContentTypes) {
        StorageProperties properties = new StorageProperties(Duration.ofMinutes(5), URI.create(CDN_ENDPOINT), null, MAX_FILE_SIZE, List.of(allowedContentTypes));
        return new MessageService(messageRepository, messageMapper, storageService, channelStub, eventPublisher, properties, transactionTemplate);
    }

    private CreateAttachmentRequest createAttachmentRequest(CreateAttachmentRequest.AttachmentMetadata... files) {
        CreateAttachmentRequest request = new CreateAttachmentRequest();
        request.setUserId(UUID.randomUUID());
        request.setChannelId(UUID.randomUUID());
        request.setFiles(List.of(files));
        return request;
    }

    private CreateAttachmentRequest.AttachmentMetadata file(int id, String fileName, String contentType, long size) {
        return new CreateAttachmentRequest.AttachmentMetadata(size, fileName, contentType, id);
    }

    private CanUserAttachFilesResponse createCanUserAttachFilesResponse(int status, String msg) {
        return CanUserAttachFilesResponse.newBuilder()
                .setStatus(status)
                .setData(status == HttpStatus.OK.value())
                .setMessage(msg)
                .build();
    }

    private void stubAnyPresignedUpload() throws Exception {
        Mockito.when(storageService.createPutPresignedURL(Mockito.any(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(new StorageService.PresignedUpload("pending/u/key", new URL("https://storage.test/upload"), Instant.now()));
    }

    private void assertNothingRequested() {
        Mockito.verify(channelStub, Mockito.never()).canUserAttachFiles(Mockito.any());
        Mockito.verifyNoInteractions(storageService);
    }

    @Test
    @DisplayName("Happy path: given allowed files, should return one upload per file with the client's ids")
    void createAttachment_ValidFiles_ReturnsUploadPerFile() throws Exception {
        CreateAttachmentRequest request = createAttachmentRequest(
                file(7, "cat.png", "image/png", 1024),
                file(9, "notes.pdf", "application/pdf", 2048));
        Instant expiresAt = Instant.parse("2026-10-06T15:05:00Z");
        StorageService.PresignedUpload catUpload = new StorageService.PresignedUpload("pending/u/cat-key.png", new URL("https://storage.test/cat"), expiresAt);
        StorageService.PresignedUpload notesUpload = new StorageService.PresignedUpload("pending/u/notes-key.pdf", new URL("https://storage.test/notes"), expiresAt);

        Mockito.when(channelStub.canUserAttachFiles(Mockito.any())).thenReturn(createCanUserAttachFilesResponse(HttpStatus.OK.value(), ""));
        Mockito.when(storageService.createPutPresignedURL(request.getUserId(), "cat.png", "image/png")).thenReturn(catUpload);
        Mockito.when(storageService.createPutPresignedURL(request.getUserId(), "notes.pdf", "application/pdf")).thenReturn(notesUpload);

        CreateAttachmentResponse response = attachmentService(MediaType.ALL).createAttachment(request);

        Assertions.assertEquals(List.of(
                new CreateAttachmentResponse.AttachmentUpload(7, catUpload.key(), catUpload.url(), expiresAt),
                new CreateAttachmentResponse.AttachmentUpload(9, notesUpload.key(), notesUpload.url(), expiresAt)
        ), response.getAttachments());

        ArgumentCaptor<CanUserAttachFilesRequest> captor = ArgumentCaptor.forClass(CanUserAttachFilesRequest.class);
        Mockito.verify(channelStub).canUserAttachFiles(captor.capture());
        Assertions.assertEquals(request.getUserId().toString(), captor.getValue().getUserId());
        Assertions.assertEquals(request.getChannelId().toString(), captor.getValue().getChannelId());
    }

    @Test
    @DisplayName("Edge case: given a file exactly at the size limit, should accept it")
    void createAttachment_FileAtSizeLimit_IsAccepted() throws Exception {
        CreateAttachmentRequest request = createAttachmentRequest(file(0, "big.png", "image/png", MAX_FILE_SIZE.toBytes()));

        Mockito.when(channelStub.canUserAttachFiles(Mockito.any())).thenReturn(createCanUserAttachFilesResponse(HttpStatus.OK.value(), ""));
        stubAnyPresignedUpload();

        Assertions.assertDoesNotThrow(() -> attachmentService(MediaType.ALL).createAttachment(request));
    }

    @Test
    @DisplayName("Unhappy path: given a file over the size limit, should throw bad request before checking permissions")
    void createAttachment_FileTooLarge_ThrowsBadRequest() {
        CreateAttachmentRequest request = createAttachmentRequest(file(0, "big.png", "image/png", MAX_FILE_SIZE.toBytes() + 1));

        Assertions.assertThrows(BadRequestException.class, () -> attachmentService(MediaType.ALL).createAttachment(request));

        assertNothingRequested();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a type", "image/*", "*/*", "image/png; charset=binary"})
    @DisplayName("Unhappy path: given a malformed or wildcard content type, should throw bad request before checking permissions")
    void createAttachment_InvalidContentType_ThrowsBadRequest(String contentType) {
        CreateAttachmentRequest request = createAttachmentRequest(file(0, "file.png", contentType, 1024));

        Assertions.assertThrows(BadRequestException.class, () -> attachmentService(MediaType.ALL).createAttachment(request));

        assertNothingRequested();
    }

    @Test
    @DisplayName("Unhappy path: given a content type outside the allowlist, should throw bad request before checking permissions")
    void createAttachment_ContentTypeNotAllowed_ThrowsBadRequest() {
        CreateAttachmentRequest request = createAttachmentRequest(file(0, "setup.exe", "application/x-msdownload", 1024));

        Assertions.assertThrows(BadRequestException.class,
                () -> attachmentService(MediaType.parseMediaType("image/*"), MediaType.APPLICATION_PDF).createAttachment(request));

        assertNothingRequested();
    }

    @Test
    @DisplayName("Happy path: given a content type matching a wildcard in the allowlist, should accept it regardless of case")
    void createAttachment_ContentTypeMatchesWildcard_IsAccepted() throws Exception {
        CreateAttachmentRequest request = createAttachmentRequest(file(0, "cat.png", "IMAGE/PNG", 1024));

        Mockito.when(channelStub.canUserAttachFiles(Mockito.any())).thenReturn(createCanUserAttachFilesResponse(HttpStatus.OK.value(), ""));
        stubAnyPresignedUpload();

        Assertions.assertDoesNotThrow(() -> attachmentService(MediaType.parseMediaType("image/*")).createAttachment(request));
    }

    @Test
    @DisplayName("Unhappy path: given one invalid file among valid ones, should sign no uploads at all")
    void createAttachment_OneInvalidFile_SignsNothing() {
        CreateAttachmentRequest request = createAttachmentRequest(
                file(0, "cat.png", "image/png", 1024),
                file(1, "big.png", "image/png", MAX_FILE_SIZE.toBytes() + 1));

        Assertions.assertThrows(BadRequestException.class, () -> attachmentService(MediaType.ALL).createAttachment(request));

        assertNothingRequested();
    }

    @Test
    @DisplayName("Unhappy path: when the user can't attach files, should throw forbidden without signing uploads")
    void createAttachment_PermissionDenied_ThrowsForbidden() {
        CreateAttachmentRequest request = createAttachmentRequest(file(0, "cat.png", "image/png", 1024));

        Mockito.when(channelStub.canUserAttachFiles(Mockito.any()))
                .thenReturn(createCanUserAttachFilesResponse(HttpStatus.FORBIDDEN.value(), "User is not allowed to attach files"));

        ForbiddenException e = Assertions.assertThrows(ForbiddenException.class, () -> attachmentService(MediaType.ALL).createAttachment(request));

        Assertions.assertEquals("User is not allowed to attach files", e.getMessage());
        Mockito.verifyNoInteractions(storageService);
    }

    @Test
    @DisplayName("Unhappy path: when guild-service rejects the channel, should throw bad request without signing uploads")
    void createAttachment_GuildServiceBadRequest_ThrowsBadRequest() {
        CreateAttachmentRequest request = createAttachmentRequest(file(0, "cat.png", "image/png", 1024));

        Mockito.when(channelStub.canUserAttachFiles(Mockito.any()))
                .thenReturn(createCanUserAttachFilesResponse(HttpStatus.BAD_REQUEST.value(), "Channel does not exist"));

        Assertions.assertThrows(BadRequestException.class, () -> attachmentService(MediaType.ALL).createAttachment(request));

        Mockito.verifyNoInteractions(storageService);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 204, 500})
    @DisplayName("Unhappy path: when guild-service fails or returns an unexpected status, should throw an INTERNAL gRPC error")
    void createAttachment_UnexpectedStatus_ThrowsInternal(int status) {
        CreateAttachmentRequest request = createAttachmentRequest(file(0, "cat.png", "image/png", 1024));

        Mockito.when(channelStub.canUserAttachFiles(Mockito.any())).thenReturn(createCanUserAttachFilesResponse(status, "boom"));

        StatusRuntimeException e = Assertions.assertThrows(StatusRuntimeException.class, () -> attachmentService(MediaType.ALL).createAttachment(request));

        Assertions.assertEquals(Status.Code.INTERNAL, e.getStatus().getCode());
        Mockito.verifyNoInteractions(storageService);
    }

    // --- createMessage with presigned-upload keys ---

    private CreateMessageRequest createRequestWithKeys(String content, String... fileNames) {
        CreateMessageRequest req = createRequest(content);
        List<CreateMessageRequest.AttachmentKey> keys = new ArrayList<>();
        for (String fileName : fileNames) {
            keys.add(new CreateMessageRequest.AttachmentKey(pendingKey(req.getSenderId()), fileName));
        }
        req.setAttachments(keys);
        return req;
    }

    private static String pendingKey(UUID ownerId) {
        return "pending/" + ownerId + "/" + UUID.randomUUID() + ".png";
    }

    private void allowSendAndAttach() {
        Mockito.when(channelStub.canUserSendMessage(Mockito.any())).thenReturn(createCanUserSendMessageResponse(true, HttpStatus.OK.value(), ""));
        Mockito.when(channelStub.canUserAttachFiles(Mockito.any())).thenReturn(createCanUserAttachFilesResponse(HttpStatus.OK.value(), ""));
    }

    private void stubSaveAssignsId() {
        Mockito.when(messageRepository.save(ArgumentMatchers.any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0, Message.class);
            if (message.getId() == null) message.setId(UUID.randomUUID());
            return message;
        });
    }

    @Test
    @DisplayName("Happy path: given an uploaded key, should store the copied key with the size and type S3 reports, then delete the pending original")
    void createMessage_UploadedKey_StoresCopyAndDeletesOriginal() {
        CreateMessageRequest req = createRequestWithKeys("look", "cat.png");
        String pending = req.getAttachments().get(0).key();
        String permanent = "messages/attachments/" + req.getChannelId() + "/copied.png";

        allowSendAndAttach();
        stubSaveAssignsId();
        Mockito.when(storageService.findObject(pending)).thenReturn(Optional.of(new StorageService.StoredObject(2048, "image/png")));
        Mockito.when(storageService.copyToAttachments(pending, req.getChannelId())).thenReturn(permanent);

        MessageResponse result = attachmentService(MediaType.ALL).createMessage(req);

        Assertions.assertEquals(1, result.getAttachments().size());
        Assertions.assertEquals(CDN_ENDPOINT + "/" + permanent, result.getAttachments().get(0).getUrl());
        Assertions.assertEquals("cat.png", result.getAttachments().get(0).getFilename());
        Assertions.assertEquals(2048, result.getAttachments().get(0).getSize());
        Assertions.assertEquals("image/png", result.getAttachments().get(0).getType());

        Mockito.verify(storageService).deleteFileQuietly(pending);
        Mockito.verify(storageService, Mockito.never()).deleteFileQuietly(permanent);
        Mockito.verify(eventPublisher).publishEvent(new MessageCreatedEvent(result));
    }

    @Test
    @DisplayName("Happy path: given no attachments, should not check the attach-files permission")
    void createMessage_NoAttachments_SkipsAttachPermission() {
        CreateMessageRequest req = createRequest("just text");

        Mockito.when(channelStub.canUserSendMessage(Mockito.any())).thenReturn(createCanUserSendMessageResponse(true, HttpStatus.OK.value(), ""));
        stubSaveAssignsId();

        attachmentService(MediaType.ALL).createMessage(req);

        Mockito.verify(channelStub, Mockito.never()).canUserAttachFiles(Mockito.any());
        Mockito.verify(storageService, Mockito.never()).findObject(Mockito.any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"someone-else", "nested", "bare-prefix", "other-folder"})
    @DisplayName("Unhappy path: given a key that isn't the sender's pending upload, should throw bad request before any remote call")
    void createMessage_KeyNotOwned_ThrowsBadRequest(String variant) {
        CreateMessageRequest req = createRequest("look");
        UUID sender = req.getSenderId();
        String key = switch (variant) {
            case "someone-else" -> pendingKey(UUID.randomUUID());
            case "nested" -> "pending/" + sender + "/sub/" + UUID.randomUUID() + ".png";
            case "bare-prefix" -> "pending/" + sender + "/";
            default -> "messages/attachments/" + UUID.randomUUID() + "/x.png";
        };
        req.setAttachments(List.of(new CreateMessageRequest.AttachmentKey(key, "cat.png")));

        Assertions.assertThrows(BadRequestException.class, () -> attachmentService(MediaType.ALL).createMessage(req));

        Mockito.verifyNoInteractions(channelStub);
        Mockito.verify(storageService, Mockito.never()).findObject(Mockito.any());
    }

    @Test
    @DisplayName("Unhappy path: given the same key twice, should throw bad request before any remote call")
    void createMessage_DuplicateKey_ThrowsBadRequest() {
        CreateMessageRequest req = createRequest("look");
        String key = pendingKey(req.getSenderId());
        req.setAttachments(List.of(
                new CreateMessageRequest.AttachmentKey(key, "cat.png"),
                new CreateMessageRequest.AttachmentKey(key, "cat-again.png")));

        Assertions.assertThrows(BadRequestException.class, () -> attachmentService(MediaType.ALL).createMessage(req));

        Mockito.verifyNoInteractions(channelStub);
    }

    @Test
    @DisplayName("Unhappy path: when the user can't attach files, should throw forbidden without touching S3")
    void createMessage_AttachPermissionDenied_ThrowsForbidden() {
        CreateMessageRequest req = createRequestWithKeys("look", "cat.png");

        Mockito.when(channelStub.canUserSendMessage(Mockito.any())).thenReturn(createCanUserSendMessageResponse(true, HttpStatus.OK.value(), ""));
        Mockito.when(channelStub.canUserAttachFiles(Mockito.any()))
                .thenReturn(createCanUserAttachFilesResponse(HttpStatus.FORBIDDEN.value(), "User is not allowed to attach files"));

        Assertions.assertThrows(ForbiddenException.class, () -> attachmentService(MediaType.ALL).createMessage(req));

        Mockito.verify(storageService, Mockito.never()).findObject(Mockito.any());
        Mockito.verify(messageRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    @DisplayName("Unhappy path: given a key with nothing uploaded, should throw bad request without copying")
    void createMessage_ObjectMissing_ThrowsBadRequest() {
        CreateMessageRequest req = createRequestWithKeys("look", "cat.png");

        allowSendAndAttach();
        Mockito.when(storageService.findObject(Mockito.any())).thenReturn(Optional.empty());

        Assertions.assertThrows(BadRequestException.class, () -> attachmentService(MediaType.ALL).createMessage(req));

        Mockito.verify(storageService, Mockito.never()).copyToAttachments(Mockito.any(), Mockito.any());
        Mockito.verify(messageRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    @DisplayName("Unhappy path: given an uploaded object over the size limit, should throw bad request without copying")
    void createMessage_StoredObjectTooLarge_ThrowsBadRequest() {
        CreateMessageRequest req = createRequestWithKeys("look", "cat.png");

        allowSendAndAttach();
        Mockito.when(storageService.findObject(Mockito.any()))
                .thenReturn(Optional.of(new StorageService.StoredObject(MAX_FILE_SIZE.toBytes() + 1, "image/png")));

        Assertions.assertThrows(BadRequestException.class, () -> attachmentService(MediaType.ALL).createMessage(req));

        Mockito.verify(storageService, Mockito.never()).copyToAttachments(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Unhappy path: given an uploaded object whose real type isn't allowed, should throw bad request without copying")
    void createMessage_StoredObjectTypeNotAllowed_ThrowsBadRequest() {
        CreateMessageRequest req = createRequestWithKeys("look", "cat.png");

        allowSendAndAttach();
        Mockito.when(storageService.findObject(Mockito.any()))
                .thenReturn(Optional.of(new StorageService.StoredObject(1024, "application/x-msdownload")));

        Assertions.assertThrows(BadRequestException.class,
                () -> attachmentService(MediaType.parseMediaType("image/*")).createMessage(req));

        Mockito.verify(storageService, Mockito.never()).copyToAttachments(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Unhappy path: given one bad file among good ones, should copy none of them")
    void createMessage_OneBadObject_CopiesNothing() {
        CreateMessageRequest req = createRequestWithKeys("look", "good.png", "missing.png");

        allowSendAndAttach();
        Mockito.when(storageService.findObject(req.getAttachments().get(0).key()))
                .thenReturn(Optional.of(new StorageService.StoredObject(1024, "image/png")));
        Mockito.when(storageService.findObject(req.getAttachments().get(1).key())).thenReturn(Optional.empty());

        Assertions.assertThrows(BadRequestException.class, () -> attachmentService(MediaType.ALL).createMessage(req));

        Mockito.verify(storageService, Mockito.never()).copyToAttachments(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Unhappy path: when a later copy fails, should delete the copies already made and keep the originals")
    void createMessage_SecondCopyFails_DeletesFirstCopy() {
        CreateMessageRequest req = createRequestWithKeys("look", "a.png", "b.png");
        String firstPending = req.getAttachments().get(0).key();
        String secondPending = req.getAttachments().get(1).key();

        allowSendAndAttach();
        Mockito.when(storageService.findObject(Mockito.any())).thenReturn(Optional.of(new StorageService.StoredObject(1024, "image/png")));
        Mockito.when(storageService.copyToAttachments(firstPending, req.getChannelId())).thenReturn("messages/attachments/copy-a.png");
        Mockito.when(storageService.copyToAttachments(secondPending, req.getChannelId())).thenThrow(new RuntimeException("S3 down"));

        Assertions.assertThrows(RuntimeException.class, () -> attachmentService(MediaType.ALL).createMessage(req));

        Mockito.verify(storageService).deleteFileQuietly("messages/attachments/copy-a.png");
        Mockito.verify(storageService, Mockito.never()).deleteFileQuietly(firstPending);
        Mockito.verify(storageService, Mockito.never()).deleteFileQuietly(secondPending);
        Mockito.verify(messageRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    @DisplayName("Unhappy path: when saving the message fails, should delete the copies, keep the originals and publish nothing")
    void createMessage_SaveFails_DeletesCopiesKeepsOriginals() {
        CreateMessageRequest req = createRequestWithKeys("look", "cat.png");
        String pending = req.getAttachments().get(0).key();
        String permanent = "messages/attachments/" + req.getChannelId() + "/copied.png";

        allowSendAndAttach();
        Mockito.when(storageService.findObject(pending)).thenReturn(Optional.of(new StorageService.StoredObject(1024, "image/png")));
        Mockito.when(storageService.copyToAttachments(pending, req.getChannelId())).thenReturn(permanent);
        Mockito.when(messageRepository.save(Mockito.any())).thenThrow(new RuntimeException("DB down"));

        Assertions.assertThrows(RuntimeException.class, () -> attachmentService(MediaType.ALL).createMessage(req));

        Mockito.verify(storageService).deleteFileQuietly(permanent);
        Mockito.verify(storageService, Mockito.never()).deleteFileQuietly(pending);
        Mockito.verify(eventPublisher, Mockito.never()).publishEvent(Mockito.any(Object.class));
    }
}
