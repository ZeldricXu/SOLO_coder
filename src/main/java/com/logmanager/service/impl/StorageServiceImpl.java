package com.logmanager.service.impl;

import com.logmanager.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageServiceImpl implements StorageService {

    private final Map<String, Map<String, byte[]>> storage = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Map<String, String>>> metadataStore = new ConcurrentHashMap<>();

    @Override
    public Mono<String> uploadObject(String bucket, String key, InputStream data, Map<String, String> metadata) {
        return Mono.fromCallable(() -> {
            try {
                byte[] bytes = data.readAllBytes();
                storage.computeIfAbsent(bucket, k -> new ConcurrentHashMap<>()).put(key, bytes);
                if (metadata != null) {
                    metadataStore.computeIfAbsent(bucket, k -> new ConcurrentHashMap<>()).put(key, metadata);
                }
                log.info("Uploaded object: {}/{}", bucket, key);
                return key;
            } catch (Exception e) {
                throw new RuntimeException("Failed to upload object", e);
            }
        });
    }

    @Override
    public Mono<InputStream> downloadObject(String bucket, String key) {
        return Mono.fromCallable(() -> {
            Map<String, byte[]> bucketData = storage.get(bucket);
            if (bucketData == null || !bucketData.containsKey(key)) {
                throw new RuntimeException("Object not found: " + bucket + "/" + key);
            }
            return new ByteArrayInputStream(bucketData.get(key));
        });
    }

    @Override
    public Mono<Void> deleteObject(String bucket, String key) {
        return Mono.fromRunnable(() -> {
            Map<String, byte[]> bucketData = storage.get(bucket);
            if (bucketData != null) {
                bucketData.remove(key);
            }
            Map<String, Map<String, String>> bucketMetadata = metadataStore.get(bucket);
            if (bucketMetadata != null) {
                bucketMetadata.remove(key);
            }
            log.info("Deleted object: {}/{}", bucket, key);
        });
    }

    @Override
    public Mono<Map<String, Object>> getObjectMetadata(String bucket, String key) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();
            Map<String, byte[]> bucketData = storage.get(bucket);
            if (bucketData != null && bucketData.containsKey(key)) {
                result.put("size", bucketData.get(key).length);
            }
            Map<String, Map<String, String>> bucketMetadata = metadataStore.get(bucket);
            if (bucketMetadata != null && bucketMetadata.containsKey(key)) {
                result.put("metadata", bucketMetadata.get(key));
            }
            result.put("lastModified", Instant.now().toString());
            return result;
        });
    }

    @Override
    public Mono<String> generatePresignedUrl(String bucket, String key, long expirationSeconds) {
        return Mono.fromCallable(() -> {
            String token = UUID.randomUUID().toString();
            String url = String.format("https://storage.example.com/%s/%s?token=%s&expires=%d",
                    bucket, key, token, Instant.now().getEpochSecond() + expirationSeconds);
            log.info("Generated presigned URL for: {}/{}", bucket, key);
            return url;
        });
    }

    @Override
    public Flux<Map<String, Object>> listObjects(String bucket, String prefix) {
        return Flux.defer(() -> {
            Map<String, byte[]> bucketData = storage.getOrDefault(bucket, new ConcurrentHashMap<>());
            return Flux.fromIterable(bucketData.entrySet().stream()
                    .filter(e -> prefix == null || e.getKey().startsWith(prefix))
                    .map(e -> {
                        Map<String, Object> obj = new HashMap<>();
                        obj.put("key", e.getKey());
                        obj.put("size", e.getValue().length);
                        obj.put("lastModified", Instant.now().toString());
                        return obj;
                    })
                    .toList());
        });
    }

    @Override
    public Mono<Map<String, Object>> getBucketInfo(String bucket) {
        return Mono.fromCallable(() -> {
            Map<String, Object> info = new HashMap<>();
            info.put("name", bucket);
            Map<String, byte[]> bucketData = storage.getOrDefault(bucket, new ConcurrentHashMap<>());
            info.put("objectCount", bucketData.size());
            long totalSize = bucketData.values().stream().mapToLong(bytes -> bytes.length).sum();
            info.put("totalSize", totalSize);
            info.put("creationDate", Instant.now().toString());
            return info;
        });
    }
}
