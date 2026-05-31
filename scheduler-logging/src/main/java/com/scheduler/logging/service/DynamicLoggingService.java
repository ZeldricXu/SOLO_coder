package com.scheduler.logging.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DynamicLoggingService {

    private final Map<String, LevelChange> changeHistory = new ConcurrentHashMap<>();

    public static class LevelChange {
        public String logger;
        public String oldLevel;
        public String newLevel;
        public Instant changedAt;
        public String changedBy;

        public LevelChange(String logger, String oldLevel, String newLevel, String changedBy) {
            this.logger = logger;
            this.oldLevel = oldLevel;
            this.newLevel = newLevel;
            this.changedAt = Instant.now();
            this.changedBy = changedBy;
        }
    }

    public boolean setLogLevel(String loggerName, String levelStr, String changedBy) {
        try {
            Level level = Level.toLevel(levelStr.toUpperCase(), Level.INFO);
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

            Logger logger;
            if ("ROOT".equalsIgnoreCase(loggerName) || loggerName == null || loggerName.isEmpty()) {
                logger = context.getLogger(Logger.ROOT_LOGGER_NAME);
            } else {
                logger = context.getLogger(loggerName);
            }

            String oldLevel = logger.getLevel() != null ? logger.getLevel().toString() : "INFO";
            logger.setLevel(level);

            changeHistory.put(loggerName, new LevelChange(loggerName, oldLevel, levelStr, changedBy));
            log.info("Changed log level for '{}' from {} to {} by {}", loggerName, oldLevel, levelStr, changedBy);
            return true;
        } catch (Exception e) {
            log.error("Failed to change log level for '{}'", loggerName, e);
            return false;
        }
    }

    public String getLogLevel(String loggerName) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger;
        if ("ROOT".equalsIgnoreCase(loggerName) || loggerName == null || loggerName.isEmpty()) {
            logger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        } else {
            logger = context.getLogger(loggerName);
        }
        return logger.getLevel() != null ? logger.getLevel().toString() : "INFO";
    }

    public Map<String, String> getAllLogLevels() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Map<String, String> levels = new TreeMap<>();

        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        levels.put("ROOT", root.getLevel() != null ? root.getLevel().toString() : "INFO");

        context.getLoggerList().forEach(logger -> {
            if (logger.getLevel() != null && !logger.getName().equals(Logger.ROOT_LOGGER_NAME)) {
                levels.put(logger.getName(), logger.getLevel().toString());
            }
        });

        return levels;
    }

    public boolean resetLogLevel(String loggerName) {
        return setLogLevel(loggerName, "INFO", "system");
    }

    public void resetAll() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLoggerList().forEach(logger -> {
            if (!logger.getName().equals(Logger.ROOT_LOGGER_NAME)) {
                logger.setLevel(null);
            }
        });
        context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        log.info("Reset all log levels to default");
    }

    public List<LevelChange> getChangeHistory() {
        return new ArrayList<>(changeHistory.values());
    }
}
