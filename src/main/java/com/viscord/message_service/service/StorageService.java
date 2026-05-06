package com.viscord.message_service.service;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.viscord.message_service.enums.StoragePath;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StorageService {

    @Value("${gcs.bucket}")
    private String bucketName;

    private final Storage storage;

    public String uploadFile(MultipartFile file, StoragePath type, String entityId) {
        String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
        String key = String.format("%s/%s/%s", type.getPath(), entityId, UUID.randomUUID() + "." + extension);

        try {
            BlobId blobId = BlobId.of(this.bucketName, key);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(file.getContentType())
                    .build();

            this.storage.createFrom(blobInfo, file.getInputStream());
            return key;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file input stream", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload to GCS", e);
        }
    }

    public void deleteFile(String key) {
        try {
            this.storage.delete(BlobId.of(this.bucketName, key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file from GCS bucket", e);
        }
    }
}
