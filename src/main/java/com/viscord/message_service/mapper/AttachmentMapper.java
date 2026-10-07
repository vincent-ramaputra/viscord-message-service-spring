package com.viscord.message_service.mapper;

import com.viscord.message_service.dto.AttachmentResponse;
import com.viscord.message_service.model.message.Attachment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring", uses = {StorageUrlMapper.class})
public interface AttachmentMapper {
    @Mapping(target = "url", source = "url", qualifiedByName = "toPublicUrl")
    AttachmentResponse toDto(Attachment attachment);
    List<AttachmentResponse> toDto(List<Attachment> attachments);
}
