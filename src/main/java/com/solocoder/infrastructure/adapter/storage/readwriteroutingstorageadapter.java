package com.solocoder.infrastructure.adapter.storage;

import com.solocoder.domain.model.CoreEntity;
import com.solocoder.domain.port.StoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;

@Component
@Primary
@RequiredArgsConstructor
public class ReadWriteRoutingStorageAdapter implements StoragePort {

    @Qualifier("masterStorage")
    private final StoragePort masterStorage;

    @Qualifier("replicaStorage")
    private final StoragePort replicaStorage;

    @Override
    public Mono<String> storeFile(String fileName, InputStream content, long size, Map<String, String> metadata) {
        return masterStorage.storeFile(fileName, content, size, metadata);
    }

    @Override
    public Mono<InputStream> retrieveFile(String fileId) {
        return replicaStorage.retrieveFile(fileId)
                .switchIfEmpty(masterStorage.retrieveFile(fileId));
    }

    @Override
    public Mono<Boolean> deleteFile(String fileId) {
        return masterStorage.deleteFile(fileId);
    }

    @Override
    public Mono<CoreEntity> getFileMetadata(String fileId) {
        return replicaStorage.getFileMetadata(fileId)
                .switchIfEmpty(masterStorage.getFileMetadata(fileId));
    }

    @Override
    public Flux<CoreEntity> listFiles(String prefix, Integer page, Integer size) {
        return replicaStorage.listFiles(prefix, page, size);
    }

    @Override
    public Mono<Void> applyLifecyclePolicy(String fileId, String policyName) {
        return masterStorage.applyLifecyclePolicy(fileId, policyName);
    }

    @Override
    public Flux<CoreEntity> findExpiredFiles(Instant expirationTime) {
        return replicaStorage.findExpiredFiles(expirationTime);
    }

    @Override
    public Mono<Void> archiveFile(String fileId, String targetStorageClass) {
        return masterStorage.archiveFile(fileId, targetStorageClass);
    }
}
