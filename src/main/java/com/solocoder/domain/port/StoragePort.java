package com.solocoder.domain.port;

import com.solocoder.domain.model.CoreEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;

public interface StoragePort {

    Mono<String> storeFile(String fileName, InputStream content, long size, Map<String, String> metadata);

    Mono<InputStream> retrieveFile(String fileId);

    Mono<Boolean> deleteFile(String fileId);

    Mono<CoreEntity> getFileMetadata(String fileId);

    Flux<CoreEntity> listFiles(String prefix, Integer page, Integer size);

    Mono<Void> applyLifecyclePolicy(String fileId, String policyName);

    Flux<CoreEntity> findExpiredFiles(Instant expirationTime);

    Mono<Void> archiveFile(String fileId, String targetStorageClass);
}
