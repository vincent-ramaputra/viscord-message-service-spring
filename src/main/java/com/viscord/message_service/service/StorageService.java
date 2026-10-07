package com.viscord.message_service.service;

import com.viscord.message_service.config.StorageProperties;
import com.viscord.message_service.enums.StoragePath;
import io.awspring.cloud.s3.S3Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {
    public record PresignedUpload(String key, URL url, Instant expiresAt) {
    }

    /** What S3 actually holds for a key, as opposed to what a client claimed when it asked to upload. */
    public record StoredObject(long size, String contentType) {
    }

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    private final S3Client s3Client;
    private final S3Template s3Template;
    private final StorageProperties storageProperties;
    private final Clock clock;

    public void deleteFile(String key) {
        try {
            this.s3Template.deleteObject(this.bucketName, key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file from S3 bucket", e);
        }
    }

    /**
     * Best-effort delete for cleanup paths, where the caller is already handling another outcome
     * and a failed delete only leaves an orphaned object behind.
     */
    public void deleteFileQuietly(String key) {
        try {
            this.s3Template.deleteObject(this.bucketName, key);
        } catch (Exception e) {
            log.warn("Failed to delete {} from S3; it is now orphaned", key, e);
        }
    }

    /**
     * True if {@code key} is a pending upload issued to {@code userId}: exactly
     * pending/&lt;userId&gt;/&lt;object&gt; with no further path segments.
     */
    public boolean isPendingKeyOwnedBy(String key, UUID userId) {
        String prefix = StoragePath.PENDING.getPath() + "/" + userId + "/";
        return key != null
                && key.startsWith(prefix)
                && key.length() > prefix.length()
                && key.indexOf('/', prefix.length()) == -1;
    }

    /** Empty when nothing is stored under {@code key}: never uploaded, already attached, or expired. */
    public Optional<StoredObject> findObject(String key) {
        try {
            HeadObjectResponse head = this.s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(this.bucketName)
                    .key(key)
                    .build());
            return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            // HEAD responses have no body, so some S3-compatible stores report a missing key only as a 404.
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new RuntimeException("Failed to read object metadata from S3", e);
        }
    }

    /**
     * Copies a pending upload to its permanent attachment key and returns that key. The pending
     * object is left in place; the caller deletes it once the message is committed.
     */
    public String copyToAttachments(String pendingKey, UUID channelId) {
        String extension = StringUtils.getFilenameExtension(pendingKey);
        UUID objectId = UUID.randomUUID();
        String destinationKey = String.format("%s/%s/%s", StoragePath.ATTACHMENT.getPath(), channelId,
                StringUtils.hasText(extension) ? objectId + "." + extension : objectId);

        try {
            this.s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(this.bucketName)
                    .sourceKey(pendingKey)
                    .destinationBucket(this.bucketName)
                    .destinationKey(destinationKey)
                    .build());
            return destinationKey;
        } catch (Exception e) {
            throw new RuntimeException("Failed to copy " + pendingKey + " in S3", e);
        }
    }

    public PresignedUpload createPutPresignedURL(UUID userId, String fileName, String contentType) {
        String extension = StringUtils.getFilenameExtension(fileName);
        UUID objectId = UUID.randomUUID();
        String key = String.format("%s/%s/%s", StoragePath.PENDING.getPath(), userId, StringUtils.hasText(extension) ? objectId + "." + extension : objectId);

        URL url = this.s3Template.createSignedPutURL(this.bucketName, key, storageProperties.uploadUrlTtl(), null, contentType);

        return new PresignedUpload(key, url, Instant.now(clock).plus(storageProperties.uploadUrlTtl()));
    }

}
