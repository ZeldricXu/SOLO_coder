package com.datastandard.modules.logging;

import com.datastandard.modules.logging.dto.LogLevelChangeRequest;
import com.datastandard.modules.logging.dto.LoggerStatusResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoggerConfiguration {

    private final ObjectMapper objectMapper;

    private static final String CONFIG_FILE_PATH = "config/log-levels.json";
    private static final String DEFAULT_CONFIG = "{}";

    private final Map<String, LogLevelConfig> configuredLevels = new ConcurrentHashMap<>();
    private final Cache<String, String> levelCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    @PostConstruct
    public void init() {
        loadPersistentConfig();
    }

    public Mono<Boolean> saveConfig(String packagePath, String level, boolean persistent) {
        return Mono.fromCallable(() -> {
            LogLevelConfig config = LogLevelConfig.builder()
                    .packagePath(packagePath)
                    .level(level.toUpperCase())
                    .persistent(persistent)
                    .configuredAt(Instant.now())
                    .build();

            configuredLevels.put(packagePath, config);
            levelCache.put(packagePath, level.toUpperCase());

            if (persistent) {
                savePersistentConfig();
            }

            return true;
        });
    }

    public Mono<Boolean> removeConfig(String packagePath) {
        return Mono.fromCallable(() -> {
            configuredLevels.remove(packagePath);
            levelCache.invalidate(packagePath);
            savePersistentConfig();
            return true;
        });
    }

    public Mono<LogLevelConfig> getConfig(String packagePath) {
        return Mono.fromCallable(() -> {
            String cached = levelCache.getIfPresent(packagePath);
            if (cached != null) {
                return configuredLevels.get(packagePath);
            }
            return configuredLevels.get(packagePath);
        });
    }

    public Flux<LogLevelConfig> getAllConfigs() {
        return Flux.fromIterable(configuredLevels.values());
    }

    public Mono<Boolean> hasConfig(String packagePath) {
        return Mono.fromCallable(() -> configuredLevels.containsKey(packagePath));
    }

    private void loadPersistentConfig() {
        try {
            File configFile = new File(CONFIG_FILE_PATH);
            if (!configFile.exists()) {
                log.info("No persistent log level config found, using defaults");
                return;
            }

            Map<String, Map<String, Object>> rawConfig = objectMapper.readValue(
                    configFile, new TypeReference<Map<String, Map<String, Object>>>() {});

            for (Map.Entry<String, Map<String, Object>> entry : rawConfig.entrySet()) {
                String packagePath = entry.getKey();
                Map<String, Object> data = entry.getValue();

                LogLevelConfig config = LogLevelConfig.builder()
                        .packagePath(packagePath)
                        .level((String) data.get("level"))
                        .persistent((Boolean) data.getOrDefault("persistent", true))
                        .configuredAt(Instant.parse((String) data.get("configuredAt")))
                        .build();

                configuredLevels.put(packagePath, config);
                levelCache.put(packagePath, config.getLevel());
            }

            log.info("Loaded {} persistent log level configurations", configuredLevels.size());
        } catch (IOException e) {
            log.error("Failed to load persistent log level config", e);
        }
    }

    private void savePersistentConfig() {
        try {
            File configDir = new File("config");
            if (!configDir.exists()) {
                configDir.mkdirs();
            }

            Map<String, Object> configToSave = new HashMap<>();
            for (Map.Entry<String, LogLevelConfig> entry : configuredLevels.entrySet()) {
                if (entry.getValue().isPersistent()) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("level", entry.getValue().getLevel());
                    data.put("persistent", entry.getValue().isPersistent());
                    data.put("configuredAt", entry.getValue().getConfiguredAt().toString());
                    configToSave.put(entry.getKey(), data);
                }
            }

            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(CONFIG_FILE_PATH), configToSave);

            log.debug("Saved {} persistent log level configurations", configToSave.size());
        } catch (IOException e) {
            log.error("Failed to save persistent log level config", e);
        }
    }

    public Mono<List<LoggerStatusResponse>> restorePersistentConfigs(LogbackLevelChanger levelChanger) {
        return Flux.fromIterable(configuredLevels.values())
                .filter(LogLevelConfig::isPersistent)
                .map(config -> {
                    boolean changed = levelChanger.changeLogLevel(config.getPackagePath(), config.getLevel());
                    return LoggerStatusResponse.builder()
                            .packagePath(config.getPackagePath())
                            .configuredLevel(config.getLevel())
                            .currentLevel(levelChanger.getCurrentLevel(config.getPackagePath()))
                            .effectiveLevel(((LogbackLevelChangerImpl) levelChanger).getEffectiveLevel(config.getPackagePath()))
                            .lastModified(config.getConfiguredAt())
                            .persistent(true)
                            .build();
                })
                .collectList();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class LogLevelConfig {
        private String packagePath;
        private String level;
        private boolean persistent;
        private Instant configuredAt;
        private Instant expiresAt;
        private String modifiedBy;
    }
}
