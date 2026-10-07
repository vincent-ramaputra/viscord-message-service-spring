package com.viscord.message_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class CreateMessageRequest {
    /**
     * A file the client already uploaded through POST /channels/{channelId}/attachments.
     * {@code key} is the pending key that endpoint returned; {@code fileName} is what users see.
     */
    public record AttachmentKey(
            @NotBlank String key,
            @NotBlank @Size(max = 255) String fileName
    ) {
    }

    private String content;

    private UUID channelId;

    private List<UUID> mentions = new ArrayList<>();

    private UUID senderId;

    @Size(max = 10)
    private List<@Valid AttachmentKey> attachments = new ArrayList<>();
}
