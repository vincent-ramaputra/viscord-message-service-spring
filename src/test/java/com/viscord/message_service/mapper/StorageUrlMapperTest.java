package com.viscord.message_service.mapper;

import com.viscord.message_service.config.StorageProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.time.Duration;
import java.util.List;

public class StorageUrlMapperTest {
    private static final String KEY = "messages/attachments/c1/a.png";

    private static StorageUrlMapper mapperWithCdn(URI cdnEndpoint) {
        StorageProperties properties = new StorageProperties(Duration.ofMinutes(5), cdnEndpoint, null, DataSize.ofMegabytes(25), List.of(MediaType.ALL));
        return new StorageUrlMapper(properties);
    }

    // The first row is the local setup (CDN_ENDPOINT=https://localhost:3002/cdn): a base path with
    // no trailing slash, where joining without a separator would produce /cdnmessages/...
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "https://localhost:3002/cdn,  https://localhost:3002/cdn/messages/attachments/c1/a.png",
            "https://localhost:3002/cdn/, https://localhost:3002/cdn/messages/attachments/c1/a.png",
            "https://cdn.dev.viscord.app, https://cdn.dev.viscord.app/messages/attachments/c1/a.png",
            "https://cdn.dev.viscord.app/, https://cdn.dev.viscord.app/messages/attachments/c1/a.png"
    })
    @DisplayName("Happy path: should join the CDN endpoint and the key with exactly one slash")
    void toPublicURL_JoinsEndpointAndKey(String cdnEndpoint, String expected) {
        Assertions.assertEquals(expected, mapperWithCdn(URI.create(cdnEndpoint)).toPublicUrl(KEY));
    }

    @Test
    @DisplayName("Edge case: characters that aren't valid in a URL path are percent-encoded")
    void toPublicURL_KeyWithSpace_IsEncoded() {
        String url = mapperWithCdn(URI.create("https://cdn.dev.viscord.app")).toPublicUrl("messages/attachments/c1/my photo.png");

        Assertions.assertEquals("https://cdn.dev.viscord.app/messages/attachments/c1/my%20photo.png", url);
    }
}
