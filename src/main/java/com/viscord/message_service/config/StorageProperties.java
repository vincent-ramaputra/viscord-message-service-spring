package com.viscord.message_service.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "viscord.storage")
public record StorageProperties(
        @NotNull Duration uploadUrlTtl,

        @NotNull URI cdnEndpoint,
        URI publicEndpoint,

        @NotNull DataSize maxFileSize,

        // May contain wildcards such as image/*; use */* to allow any type.
        @NotEmpty List<MediaType> allowedContentTypes
) {
    public StorageProperties {
        if (cdnEndpoint != null && (cdnEndpoint.getScheme() == null || cdnEndpoint.getHost() == null)) {
            throw new IllegalArgumentException("viscord.storage.cdn-endpoint must be an absolute http(s) URL, got " + cdnEndpoint);
        }
    }
}
