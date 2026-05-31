package com.solocoder.platform.storage.cache;

import com.solocoder.platform.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class StorageCacheWarmupRunner implements ApplicationRunner {

    private final StorageCacheManager cacheManager;
    private final StorageService storageService;

    @Value("${storage.cache.warmup-keys:}")
    private List<String> warmupKeys;

    @Override
    public void run(ApplicationArguments args) {
        if (warmupKeys == null || warmupKeys.isEmpty()) {
            log.info("No storage cache warmup keys configured, skipping warmup");
            return;
        }

        log.info("Starting storage cache warmup with {} keys", warmupKeys.size());
        int warmed = 0;
        for (String key : warmupKeys) {
            try {
                storageService.get(key).ifPresent(item -> {
                    cacheManager.warmup(key, item);
                });
                warmed++;
            } catch (Exception e) {
                log.warn("Failed to warmup storage cache for key: {}", key, e);
            }
        }
        log.info("Storage cache warmup completed: {}/{} keys warmed", warmed, warmupKeys.size());
    }
}
