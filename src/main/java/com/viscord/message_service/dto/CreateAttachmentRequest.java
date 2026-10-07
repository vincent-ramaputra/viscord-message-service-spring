package com.viscord.message_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


@Data
public class CreateAttachmentRequest {
    public record AttachmentMetadata(
            @Positive long size,
            @NotBlank String fileName,
            @NotBlank String contentType,
            int id
    ) {}

    UUID userId;
    UUID channelId;
    // @Valid on the element type makes the constraints inside AttachmentMetadata apply to each file.
    @Size(min = 1, max = 10) List<@Valid AttachmentMetadata> files = new ArrayList<>();
}
