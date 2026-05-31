package com.solocoder.platform.logging.recovery;

import com.solocoder.platform.logging.cache.MultiLevelCacheManager;
import com.solocoder.platform.logging.model.LogLevelConfig;
import com.solocoder.platform.logging.persistence.LogLevelPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogLevelRecoveryRunner implements ApplicationRunner {

    private final LogLevelPersistenceService persistenceService;
    private final MultiLevelCacheManager cacheManager;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting log level recovery from persistent store...");
        List<LogLevelConfig> persisted = persistenceService.loadAll();
        int recovered = 0;
        for (LogLevelConfig config : persisted) {
            try {
                applyLogLevel(config.getLoggerName(), config.getLevel());
                cacheManager.put(config.getLoggerName(), config);
                recovered++;
                log.info("Recovered log level: logger={}, level={}", config.getLoggerName(), config.getLevel());
            } catch (Exception e) {
                log.error("Failed to recover log level: logger={}, level={}", config.getLoggerName(), config.getLevel(), e);
            }
        }
        log.info("Log level recovery completed: recovered {} of {} configs", recovered, persisted.size());
    }

    private void applyLogLevel(String loggerName, String level) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(loggerName);
        ch.qos.logback.classic.Level logbackLevel = ch.qos.logback.classic.Level.toLevel(level, ch.qos.logback.classic.Level.INFO);
        logger.setLevel(logbackLevel);
    }
}
