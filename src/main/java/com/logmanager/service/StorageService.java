package com.logmanager.service;

import reactor.core.publisher.Mono;
import java.io.InputStream;
import java.util.Map;

public interface StorageService {
    Mono<String> uploadObject(String bucket, String key, InputStream data, Map<String, String> metadata);
    Mono<InputStream> downloadObject(String bucket, String key);
    Mono<Void> deleteObject(String bucket, String key);
    Mono<Map<String, Object>> getObjectMetadata(String bucket, String key);
    Mono<String> generatePresignedUrl(String bucket, String key, long expirationSeconds);
    Flux<Map<String, Object>> listObjects(String bucket, String prefix);
    Mono<Map<String, Object>> getBucketInfo(String bucket);
}
