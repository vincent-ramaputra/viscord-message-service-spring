package com.viscord.message_service.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class EditMessageRequest {
    private UUID messageId;
    private UUID userId;
    private String content;
    private List<UUID> attachments;
}
