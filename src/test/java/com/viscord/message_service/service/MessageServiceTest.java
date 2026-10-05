package com.viscord.message_service.service;

import com.viscord.message_service.dto.CreateMessageRequest;
import com.viscord.message_service.dto.EditMessageRequest;
import com.viscord.message_service.dto.MessageResponse;
import com.viscord.message_service.exception.BadRequestException;
import com.viscord.message_service.exception.ForbiddenException;
import com.viscord.message_service.exception.NotFoundException;
import com.viscord.message_service.grpc.CanUserDeleteMessageResponse;
import com.viscord.message_service.grpc.CanUserSendMessageResponse;
import com.viscord.message_service.grpc.ChannelsServiceGrpc;
import com.viscord.message_service.grpc.CheckPermissionResponse;
import com.viscord.message_service.mapper.AttachmentMapper;
import com.viscord.message_service.mapper.MessageMapper;
import com.viscord.message_service.messaging.MessageCreatedEvent;
import com.viscord.message_service.model.message.Attachment;
import com.viscord.message_service.model.message.Message;
import com.viscord.message_service.repository.MessageRepository;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

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

    @InjectMocks
    private MessageService messageService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(messageMapper, "attachmentMapper", attachmentMapper);
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

        Mockito.when(channelStub.checkPermission(Mockito.any())).thenReturn(
                CheckPermissionResponse.newBuilder().setAllowed(false).build());

        Assertions.assertThrows(ForbiddenException.class, () -> {
            List<MessageResponse> result = messageService.getChannelMessages(userId, channelId);
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
        Mockito.when(channelStub.checkPermission(Mockito.any())).thenReturn(
                CheckPermissionResponse.newBuilder().setAllowed(true).build());

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
    @DisplayName("Happy path: given empty content but with attachment, should save message")
    void createMessage_EmptyContentWithAttachment_ReturnsMessageResponse() {
        CreateMessageRequest req = createRequest("");
        req.setAttachments(List.of(new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes())));

        Mockito.when(channelStub.canUserSendMessage(Mockito.any())).thenReturn(createCanUserSendMessageResponse(true, HttpStatus.OK.value(), ""));
        Mockito.when(messageRepository.save(ArgumentMatchers.any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0, Message.class);
            message.setId(UUID.randomUUID());

            return message;
        });
        Mockito.when(storageService.uploadFile(Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            MultipartFile file = invocation.getArgument(0, MultipartFile.class);

            return String.format("example/key/%s", file.getOriginalFilename());
        });

        MessageResponse result = messageService.createMessage(req);

        Mockito.verify(messageMapper).toEntity(req);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(req.getSenderId(), result.getSenderId());
        Assertions.assertEquals(req.getContent(), result.getContent());
        Assertions.assertEquals(req.getAttachments().size(), result.getAttachments().size());
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
}
