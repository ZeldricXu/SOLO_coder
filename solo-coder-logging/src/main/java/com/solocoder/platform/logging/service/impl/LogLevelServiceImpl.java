package com.solocoder.platform.logging.service.impl;

import com.solocoder.platform.common.exception.BusinessException;
import com.solocoder.platform.logging.cache.MultiLevelCacheManager;
import com.solocoder.platform.logging.event.LogLevelChangeEvent;
import com.solocoder.platform.logging.model.LogLevelAdjustRequest;
import com.solocoder.platform.logging.model.LogLevelConfig;
import com.solocoder.platform.logging.service.LogLevelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogLevelServiceImpl implements LogLevelService {

    private final MultiLevelCacheManager cacheManager;
    private final ApplicationEventPublisher eventPublisher;
    private final com.solocoder.platform.logging.persistence.LogLevelPersistenceService persistenceService;

    @Override
    public LogLevelConfig adjustLogLevel(LogLevelAdjustRequest request) {
        validateLevel(request.getLevel());

        Optional<LogLevelConfig> existing = cacheManager.get(request.getLoggerName());
        String oldLevel = existing.map(LogLevelConfig::getLevel).orElse("INFO");

        LogLevelConfig config = LogLevelConfig.builder()
                .loggerName(request.getLoggerName())
                .level(request.getLevel().toUpperCase())
                .scope(request.getScope())
                .ttlSeconds(request.getTtlSeconds())
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusSeconds(request.getTtlSeconds()))
                .source("API")
                .build();

        applyLogLevel(config.getLoggerName(), config.getLevel());
        cacheManager.put(request.getLoggerName(), config);
        persistenceService.save(config);

        eventPublisher.publishEvent(
                LogLevelChangeEvent.of(request.getLoggerName(), oldLevel, config.getLevel(), "API", cacheManager.getL1Cache().getNodeId())
        );

        log.info("Log level adjusted: logger={}, oldLevel={}, newLevel={}", request.getLoggerName(), oldLevel, config.getLevel());
        return config;
    }

    @Override
    public Optional<LogLevelConfig> getLogLevel(String loggerName) {
        return cacheManager.get(loggerName);
    }

    @Override
    public List<LogLevelConfig> getAllLogLevels() {
        Map<String, LogLevelConfig> all = cacheManager.getAll();
        return new ArrayList<>(all.values());
    }

    @Override
    public void resetLogLevel(String loggerName) {
        Optional<LogLevelConfig> existing = cacheManager.get(loggerName);
        String oldLevel = existing.map(LogLevelConfig::getLevel).orElse("INFO");

        cacheManager.invalidate(loggerName);
        persistenceService.delete(loggerName);
        applyLogLevel(loggerName, "INFO");

        eventPublisher.publishEvent(
                LogLevelChangeEvent.of(loggerName, oldLevel, "INFO", "RESET", cacheManager.getL1Cache().getNodeId())
        );

        log.info("Log level reset: logger={}, oldLevel={}, newLevel=INFO", loggerName, oldLevel);
    }

    @Override
    public void resetAllLogLevels() {
        Map<String, LogLevelConfig> all = cacheManager.getAll();
        for (Map.Entry<String, LogLevelConfig> entry : all.entrySet()) {
            applyLogLevel(entry.getKey(), "INFO");
        }
        cacheManager.invalidateAll();
        persistenceService.deleteAll();
        log.info("All log levels reset to INFO");
    }

    @Override
    public List<LogLevelConfig> recoverFromPersistence() {
        List<LogLevelConfig> persisted = persistenceService.loadAll();
        List<LogLevelConfig> recovered = new ArrayList<>();
        for (LogLevelConfig config : persisted) {
            try {
                applyLogLevel(config.getLoggerName(), config.getLevel());
                cacheManager.put(config.getLoggerName(), config);
                recovered.add(config);
                log.info("Recovered log level: logger={}, level={}", config.getLoggerName(), config.getLevel());
            } catch (Exception e) {
                log.error("Failed to recover log level: logger={}", config.getLoggerName(), e);
            }
        }
        log.info("Recovery from persistence completed: {}/{} configs recovered", recovered.size(), persisted.size());
        return recovered;
    }

    private void applyLogLevel(String loggerName, String level) {
        try {
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                    LoggerFactory.getLogger(loggerName);
            ch.qos.logback.classic.Level logbackLevel = ch.qos.logback.classic.Level.toLevel(level, ch.qos.logback.classic.Level.INFO);
            logger.setLevel(logbackLevel);
        } catch (Exception e) {
            log.error("Failed to apply log level: logger={}, level={}", loggerName, level, e);
            throw new BusinessException("Failed to apply log level: " + e.getMessage());
        }
    }

    private void validateLevel(String level) {
        try {
            ch.qos.logback.classic.Level.toLevel(level.toUpperCase());
        } catch (Exception e) {
            throw new BusinessException("Invalid log level: " + level);
        }
    }
}
