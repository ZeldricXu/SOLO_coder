package com.solocoder.application.service;

import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.model.CoreEntity;
import com.solocoder.domain.port.StructuredLoggerPort;
import com.solocoder.domain.port.StoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StorageService {

    private final StoragePort storagePort;
    private final StructuredLoggerPort logger;

    public Mono<ApiResponse<Map<String, Object>>> storeFile(String fileName, InputStream content,
                                                            long size, Map<String, String> metadata) {
        Map<String, Object> context = Map.of(
                "traceId", UUID.randomUUID().toString(),
                "fileName", fileName,
                "fileSize", size
        );
        logger.info("开始存储文件", context);

        return storagePort.storeFile(fileName, content, size, metadata)
                .map(fileId -> {
                    Map<String, Object> data = Map.of(
                            "id", fileId,
                            "status", "provisioning"
                    );
                    logger.info("文件存储成功", Map.of("fileId", fileId));
                    return ApiResponse.created(data);
                })
                .onErrorResume(e -> {
                    logger.error("文件存储失败", e, context);
                    return Mono.just(ApiResponse.error(500, "文件存储失败: " + e.getMessage()));
                });
    }

    public Mono<ApiResponse<CoreEntity>> getFileMetadata(String fileId) {
        return storagePort.getFileMetadata(fileId)
                .map(ApiResponse::success)
                .switchIfEmpty(Mono.just(ApiResponse.error(404, "文件不存在")));
    }

    public Mono<ApiResponse<Void>> applyLifecyclePolicy(String fileId, String policyName) {
        return storagePort.applyLifecyclePolicy(fileId, policyName)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> {
                    logger.error("应用生命周期策略失败", e, Map.of("fileId", fileId, "policy", policyName));
                    return Mono.just(ApiResponse.error(500, "应用策略失败: " + e.getMessage()));
                });
    }

    public Flux<CoreEntity> cleanupExpiredFiles() {
        Instant expirationTime = Instant.now().minus(90, ChronoUnit.DAYS);
        logger.info("开始清理过期文件", Map.of("expirationTime", expirationTime.toString()));

        return storagePort.findExpiredFiles(expirationTime)
                .flatMap(file -> storagePort.deleteFile(file.getId())
                        .thenReturn(file)
                        .onErrorResume(e -> {
                            logger.warn("删除文件失败", Map.of("fileId", file.getId(), "error", e.getMessage()));
                            return Mono.empty();
                        }));
    }

    public Mono<ApiResponse<Boolean>> deleteFile(String fileId) {
        return storagePort.deleteFile(fileId)
                .map(ApiResponse::success)
                .onErrorResume(e -> {
                    logger.error("删除文件失败", e, Map.of("fileId", fileId));
                    return Mono.just(ApiResponse.error(500, "删除失败: " + e.getMessage()));
                });
    }

    public Mono<ApiResponse<InputStream>> retrieveFile(String fileId) {
        return storagePort.retrieveFile(fileId)
                .map(ApiResponse::success)
                .switchIfEmpty(Mono.just(ApiResponse.error(404, "文件不存在")));
    }

    public Mono<ApiResponse<Flux<CoreEntity>>> listFiles(String prefix, Integer page, Integer size) {
        return Mono.just(ApiResponse.success(storagePort.listFiles(prefix, page, size)));
    }
}
