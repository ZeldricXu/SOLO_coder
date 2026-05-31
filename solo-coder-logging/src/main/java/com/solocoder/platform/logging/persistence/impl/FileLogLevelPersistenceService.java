package com.solocoder.platform.logging.persistence.impl;

import com.solocoder.platform.common.util.JsonUtils;
import com.solocoder.platform.logging.model.LogLevelConfig;
import com.solocoder.platform.logging.persistence.LogLevelPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class FileLogLevelPersistenceService implements LogLevelPersistenceService {

    private final Path persistenceDir;
    private final Map<String, LogLevelConfig> memoryStore = new ConcurrentHashMap<>();

    public FileLogLevelPersistenceService(@Value("${logging.persistence.dir:./data/log-levels}") String persistenceDir) {
        this.persistenceDir = Paths.get(persistenceDir);
        try {
            Files.createDirectories(this.persistenceDir);
            log.info("Log level persistence directory: {}", this.persistenceDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to create persistence directory: {}", persistenceDir, e);
        }
    }

    @Override
    public void save(LogLevelConfig config) {
        memoryStore.put(config.getLoggerName(), config);
        try {
            String fileName = sanitizeFileName(config.getLoggerName()) + ".json";
            Path filePath = persistenceDir.resolve(fileName);
            String json = JsonUtils.toJson(config);
            Files.writeString(filePath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Persisted log level config: logger={}", config.getLoggerName());
        } catch (IOException e) {
            log.error("Failed to persist log level config: logger={}", config.getLoggerName(), e);
        }
    }

    @Override
    public void delete(String loggerName) {
        memoryStore.remove(loggerName);
        try {
            String fileName = sanitizeFileName(loggerName) + ".json";
            Path filePath = persistenceDir.resolve(fileName);
            Files.deleteIfExists(filePath);
            log.debug("Deleted persisted log level config: logger={}", loggerName);
        } catch (IOException e) {
            log.error("Failed to delete persisted log level config: logger={}", loggerName, e);
        }
    }

    @Override
    public Optional<LogLevelConfig> load(String loggerName) {
        LogLevelConfig cached = memoryStore.get(loggerName);
        if (cached != null && !cached.isExpired()) {
            return Optional.of(cached);
        }
        try {
            String fileName = sanitizeFileName(loggerName) + ".json";
            Path filePath = persistenceDir.resolve(fileName);
            if (Files.exists(filePath)) {
                String json = Files.readString(filePath);
                LogLevelConfig config = JsonUtils.fromJson(json, LogLevelConfig.class);
                if (config != null && !config.isExpired()) {
                    memoryStore.put(loggerName, config);
                    return Optional.of(config);
                } else {
                    Files.deleteIfExists(filePath);
                    memoryStore.remove(loggerName);
                }
            }
        } catch (IOException e) {
            log.error("Failed to load persisted log level config: logger={}", loggerName, e);
        }
        return Optional.empty();
    }

    @Override
    public List<LogLevelConfig> loadAll() {
        List<LogLevelConfig> configs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(persistenceDir, "*.json")) {
            for (Path filePath : stream) {
                try {
                    String json = Files.readString(filePath);
                    LogLevelConfig config = JsonUtils.fromJson(json, LogLevelConfig.class);
                    if (config != null && !config.isExpired()) {
                        memoryStore.put(config.getLoggerName(), config);
                        configs.add(config);
                    } else {
                        Files.deleteIfExists(filePath);
                    }
                } catch (Exception e) {
                    log.warn("Failed to read persisted config: {}", filePath, e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan persistence directory", e);
        }
        return configs;
    }

    @Override
    public void deleteAll() {
        memoryStore.clear();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(persistenceDir, "*.json")) {
            for (Path filePath : stream) {
                Files.deleteIfExists(filePath);
            }
        } catch (IOException e) {
            log.error("Failed to clean persistence directory", e);
        }
        log.info("All persisted log level configs deleted");
    }

    private String sanitizeFileName(String loggerName) {
        return loggerName.replace('.', '_').replace('/', '_').replace('\\', '_');
    }
}
