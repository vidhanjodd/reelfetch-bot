package com.reelfetch_bot.service.storage;

import com.reelfetch_bot.exception.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final S3Client r2Client;

    @Value("${r2.bucket}")
    private String bucket;

    @Value("${r2.public-url}")
    private String publicUrl;

    public String upload(Path localFile) {
        String key = buildKey(localFile);
        String contentType = detectContentType(localFile);

        log.info("Uploading {} → R2 key: {}", localFile.getFileName(), key);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build();

            r2Client.putObject(request, RequestBody.fromFile(localFile));
            log.info("Upload complete: {}", key);
            return key;

        } catch (Exception e) {
            throw new StorageException("Failed to upload file to R2: " + e.getMessage(), e);
        }
    }


    public String publicUrlFor(String r2Key) {
        return publicUrl.stripTrailing() + "/" + r2Key;
    }

    public void delete(String r2Key) {
        try {
            r2Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(r2Key)
                    .build());
            log.info("Deleted R2 object: {}", r2Key);
        } catch (Exception e) {
            log.warn("Failed to delete R2 object {}: {}", r2Key, e.getMessage());
        }
    }


    private String buildKey(Path file) {
        String ext = getExtension(file.getFileName().toString());
        return "media/" + UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }

    private String detectContentType(Path file) {
        try {
            String type = Files.probeContentType(file);
            return type != null ? type : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }
}