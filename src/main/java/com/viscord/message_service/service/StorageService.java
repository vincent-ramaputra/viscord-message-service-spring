package com.viscord.message_service.service;

import com.viscord.message_service.enums.StoragePath;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
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

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    private final S3Template s3Template;

    public String uploadFile(MultipartFile file, StoragePath type, String entityId) {
        String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
        String key = String.format("%s/%s/%s", type.getPath(), entityId, UUID.randomUUID() + "." + extension);

        try {
            ObjectMetadata metadata = ObjectMetadata.builder()
                    .contentType(file.getContentType())
                    .build();

            this.s3Template.upload(this.bucketName, key, file.getInputStream(), metadata);
            return key;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file input stream", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload to S3", e);
        }
    }

    public void deleteFile(String key) {
        try {
            this.s3Template.deleteObject(this.bucketName, key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file from S3 bucket", e);
        }
    }
}
