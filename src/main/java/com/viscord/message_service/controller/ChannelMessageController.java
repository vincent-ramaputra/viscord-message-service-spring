package com.viscord.message_service.controller;


import com.viscord.message_service.dto.CreateMessageRequest;
import com.viscord.message_service.dto.EditMessageRequest;
import com.viscord.message_service.dto.MessageResponse;
import com.viscord.message_service.service.MessageService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/channels/{channelId}/messages")
public class ChannelMessageController {
    private final MessageService messageService;

    public ChannelMessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping
    public ResponseEntity<List<MessageResponse>> getChannelMessages(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID channelId
    ) {
        return ResponseEntity.ok(messageService.getChannelMessages(userId, channelId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MessageResponse> createMessage(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID channelId,
            @RequestPart(value = "attachments", required = false) List<MultipartFile> attachments,
            @Valid @RequestPart("data") CreateMessageRequest data
    ) {
        data.setSenderId(userId);
        data.setChannelId(channelId);
        if (attachments != null) data.setAttachments(attachments);

        return ResponseEntity.status(HttpStatus.CREATED).body(messageService.createMessage(data));
    }

    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteMessage(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID messageId) {

        messageService.deleteMessage(userId, messageId);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{messageId}")
    public ResponseEntity<MessageResponse> editMessage(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID messageId,
            @RequestBody EditMessageRequest request) {
        request.setMessageId(messageId);
        request.setUserId(userId);

        return ResponseEntity.status(HttpStatus.OK).body(messageService.editMessage(request));
    }
}
