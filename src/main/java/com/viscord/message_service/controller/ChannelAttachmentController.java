package com.viscord.message_service.controller;

import com.viscord.message_service.dto.CreateAttachmentRequest;
import com.viscord.message_service.dto.CreateAttachmentResponse;
import com.viscord.message_service.service.MessageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Hands out presigned URLs so clients upload attachments straight to S3; the returned keys are
 * then referenced when the message is created. Traefik must route /channels/{id}/attachments here
 * (the default for /channels/* is guild-service).
 */
@RestController
@RequestMapping("/channels/{channelId}/attachments")
public class ChannelAttachmentController {
    private final MessageService messageService;

    public ChannelAttachmentController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping
    public ResponseEntity<CreateAttachmentResponse> createAttachment(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID channelId,
            @Valid @RequestBody CreateAttachmentRequest request) {
        request.setUserId(userId);
        request.setChannelId(channelId);

        return ResponseEntity.status(HttpStatus.OK).body(this.messageService.createAttachment(request));
    }
}
