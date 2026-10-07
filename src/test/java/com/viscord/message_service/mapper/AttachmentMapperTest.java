package com.viscord.message_service.mapper;

import com.viscord.message_service.config.StorageProperties;
import com.viscord.message_service.dto.AttachmentResponse;
import com.viscord.message_service.model.message.Attachment;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Uses the generated AttachmentMapperImpl. Outside Spring nothing injects its StorageUrlMapper,
 * so the test sets it by field name, the same way MessageServiceTest wires attachmentMapper.
 */
public class AttachmentMapperTest {
    private static final String CDN = "https://cdn.test";

    private AttachmentMapper attachmentMapper;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties(Duration.ofMinutes(5), URI.create(CDN), null, DataSize.ofMegabytes(25), List.of(MediaType.ALL));
        attachmentMapper = Mappers.getMapper(AttachmentMapper.class);
        ReflectionTestUtils.setField(attachmentMapper, "storageUrlMapper", new StorageUrlMapper(properties));
    }

    private static Attachment attachment(String key, String filename) {
        Attachment attachment = new Attachment();
        attachment.setId(UUID.randomUUID());
        attachment.setFilename(filename);
        attachment.setType("image/png");
        attachment.setSize(512);
        attachment.setUrl(key);
        return attachment;
    }

    @Test
    @DisplayName("Happy path: url becomes the CDN URL and every other field is copied unchanged")
    void toDto_Attachment_UsesCdnUrl() {
        Attachment attachment = attachment("messages/attachments/c1/a.png", "cat.png");

        AttachmentResponse response = attachmentMapper.toDto(attachment);

        Assertions.assertEquals(CDN + "/messages/attachments/c1/a.png", response.getUrl());
        Assertions.assertEquals(attachment.getId(), response.getId());
        Assertions.assertEquals("cat.png", response.getFilename());
        Assertions.assertEquals("image/png", response.getType());
        Assertions.assertEquals(512, response.getSize());
    }

    @Test
    @DisplayName("Happy path: only url is transformed, so other String fields keep their values")
    void toDto_StringFields_NotPrefixed() {
        AttachmentResponse response = attachmentMapper.toDto(attachment("messages/attachments/c1/a.png", "notes.txt"));

        Assertions.assertFalse(response.getFilename().startsWith(CDN));
        Assertions.assertFalse(response.getType().startsWith(CDN));
    }

    @Test
    @DisplayName("Happy path: the list overload applies the CDN URL to every element")
    void toDto_List_UsesCdnUrlForEach() {
        List<AttachmentResponse> responses = attachmentMapper.toDto(List.of(
                attachment("messages/attachments/c1/a.png", "a.png"),
                attachment("messages/attachments/c1/b.png", "b.png")));

        Assertions.assertEquals(List.of(CDN + "/messages/attachments/c1/a.png", CDN + "/messages/attachments/c1/b.png"),
                responses.stream().map(AttachmentResponse::getUrl).toList());
    }

    @Test
    @DisplayName("Happy path: the entity keeps its storage key; the CDN URL only exists in the DTO")
    void toDto_DoesNotChangeEntity() {
        Attachment attachment = attachment("messages/attachments/c1/a.png", "cat.png");

        attachmentMapper.toDto(attachment);

        Assertions.assertEquals("messages/attachments/c1/a.png", attachment.getUrl());
    }
}
