package com.viscord.message_service.mapper;

import com.viscord.message_service.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@RequiredArgsConstructor
@Component
public class StorageUrlMapper {
    private final StorageProperties storageProperties;

    @Named("toPublicUrl")
    public String toPublicUrl(String path) {
        return UriComponentsBuilder.fromUri(this.storageProperties.cdnEndpoint())
                .path("/" + path)
                .toUriString();
    }
}
