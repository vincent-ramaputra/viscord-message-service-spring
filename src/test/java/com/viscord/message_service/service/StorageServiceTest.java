package com.viscord.message_service.service;

import com.viscord.message_service.config.StorageProperties;
import io.awspring.cloud.s3.S3Template;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * StorageService is built by hand rather than with @InjectMocks so the test controls the Clock
 * and StorageProperties directly.
 */
public class StorageServiceTest {
    private static final String BUCKET = "test-bucket";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Instant NOW = Instant.parse("2026-10-06T15:00:00Z");
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private S3Template s3Template;
    private StorageService storageService;
    private URL signedUrl;

    @BeforeEach
    void setUp() throws Exception {
        s3Template = Mockito.mock(S3Template.class);
        StorageProperties properties = new StorageProperties(TTL, null, DataSize.ofMegabytes(25), List.of(MediaType.ALL));
        storageService = new StorageService(s3Template, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        // bucketName is an @Value field, not a constructor argument.
        ReflectionTestUtils.setField(storageService, "bucketName", BUCKET);

        signedUrl = new URL("https://storage.test/signed");
        Mockito.when(s3Template.createSignedPutURL(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.anyString()))
                .thenReturn(signedUrl);
    }

    private String signedKey() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        Mockito.verify(s3Template).createSignedPutURL(Mockito.eq(BUCKET), key.capture(), Mockito.eq(TTL), Mockito.isNull(), Mockito.eq("image/png"));
        return key.getValue();
    }

    @Test
    @DisplayName("Happy path: given a file name with an extension, should sign a pending key that keeps the extension")
    void createPutPresignedURL_FileWithExtension_SignsPendingKey() {
        UUID userId = UUID.randomUUID();

        StorageService.PresignedUpload result = storageService.createPutPresignedURL(userId, "cat.png", "image/png");

        String key = signedKey();
        Assertions.assertTrue(key.matches("pending/" + userId + "/" + UUID_PATTERN + "\\.png"), key);
        Assertions.assertEquals(key, result.key());
        Assertions.assertEquals(signedUrl, result.url());
    }

    @ParameterizedTest
    @ValueSource(strings = {"README", "file."})
    @DisplayName("Edge case: given a file name without a usable extension, should sign a key without one")
    void createPutPresignedURL_NoExtension_KeyHasNoExtension(String fileName) {
        UUID userId = UUID.randomUUID();

        storageService.createPutPresignedURL(userId, fileName, "image/png");

        String key = signedKey();
        Assertions.assertTrue(key.matches("pending/" + userId + "/" + UUID_PATTERN), key);
    }

    @Test
    @DisplayName("Happy path: should not put the client's file name in the key")
    void createPutPresignedURL_FileName_NotUsedInKey() {
        storageService.createPutPresignedURL(UUID.randomUUID(), "holiday photo.png", "image/png");

        Assertions.assertFalse(signedKey().contains("holiday"));
    }

    @Test
    @DisplayName("Happy path: given the same file name twice, should sign two different keys")
    void createPutPresignedURL_SameFileNameTwice_DifferentKeys() {
        UUID userId = UUID.randomUUID();

        String first = storageService.createPutPresignedURL(userId, "cat.png", "image/png").key();
        String second = storageService.createPutPresignedURL(userId, "cat.png", "image/png").key();

        Assertions.assertNotEquals(first, second);
    }

    @Test
    @DisplayName("Happy path: should expire the upload one TTL after the current time")
    void createPutPresignedURL_FixedClock_ExpiresAfterTtl() {
        StorageService.PresignedUpload result = storageService.createPutPresignedURL(UUID.randomUUID(), "cat.png", "image/png");

        Assertions.assertEquals(NOW.plus(TTL), result.expiresAt());
    }
}
