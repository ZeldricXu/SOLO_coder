package com.solocoder.infrastructure.adapter.storage;

import com.solocoder.domain.port.StructuredLoggerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Component
@RequiredArgsConstructor
class LifecycleManager {

    private final LocalStorageAdapter localStorageAdapter;
    private final StructuredLoggerPort logger;

    @Value("${storage.lifecycle.cleanup-interval:3600000}")
    private long cleanupInterval;

    @Scheduled(fixedRateString = "${storage.lifecycle.cleanup-interval:3600000}")
    void runCleanupJob() {
        logger.info("开始执行生命周期清理任务", Map.of("timestamp", Instant.now().toString()));

        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);

        localStorageAdapter.findExpiredFiles(thirtyDaysAgo)
                .flatMap(file -> localStorageAdapter.archiveFile(file.getId(), "glacier")
                        .thenMany(Flux.just(file.getId())))
                .doOnNext(fileId -> logger.info("文件已归档", Map.of("fileId", fileId)))
                .collectList()
                .block();

        localStorageAdapter.findExpiredFiles(ninetyDaysAgo)
                .flatMap(file -> localStorageAdapter.deleteFile(file.getId())
                        .thenMany(Flux.just(file.getId())))
                .doOnNext(fileId -> logger.info("文件已删除", Map.of("fileId", fileId)))
                .collectList()
                .block();

        logger.info("生命周期清理任务完成");
    }
}
