package com.viscord.message_service.dto;


import lombok.Data;

import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class CreateAttachmentResponse {
    /**
     * Where the client should PUT one file. {@code id} echoes the id the client sent for that file;
     * {@code key} goes back in the create-message request once the upload is done.
     */
    public record AttachmentUpload(
            int id,
            String key,
            URL uploadUrl,
            Instant expiresAt
    ) {}

    List<AttachmentUpload> attachments = new ArrayList<>();
}
