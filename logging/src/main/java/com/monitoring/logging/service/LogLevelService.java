package com.monitoring.logging.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class LogLevelService {

    private final Map<String, Level> originalLevels = new ConcurrentHashMap<>();

    public Mono<Void> setLogLevel(String loggerName, String level) {
        return Mono.fromRunnable(() -> {
            Level targetLevel = Level.getLevel(level.toUpperCase());
            if (targetLevel == null) {
                throw new IllegalArgumentException("Invalid log level: " + level);
            }

            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration config = context.getConfiguration();

            LoggerConfig loggerConfig = config.getLoggerConfig(loggerName);

            if (!originalLevels.containsKey(loggerName)) {
                originalLevels.put(loggerName, loggerConfig.getLevel());
            }

            loggerConfig.setLevel(targetLevel);
            context.updateLoggers();

            log.info("Log level changed: {} -> {}", loggerName, targetLevel);
        });
    }

    public Mono<String> getLogLevel(String loggerName) {
        return Mono.fromSupplier(() -> {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration config = context.getConfiguration();
            LoggerConfig loggerConfig = config.getLoggerConfig(loggerName);
            return loggerConfig.getLevel().name();
        });
    }

    public Mono<Map<String, String>> getAllLogLevels() {
        return Mono.fromSupplier(() -> {
            Map<String, String> levels = new TreeMap<>();
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration config = context.getConfiguration();

            for (Map.Entry<String, LoggerConfig> entry : config.getLoggers().entrySet()) {
                levels.put(entry.getKey(), entry.getValue().getLevel().name());
            }

            levels.put("root", config.getRootLogger().getLevel().name());
            return levels;
        });
    }

    public Mono<Void> resetLogLevel(String loggerName) {
        return Mono.fromRunnable(() -> {
            Level originalLevel = originalLevels.remove(loggerName);
            if (originalLevel != null) {
                LoggerContext context = (LoggerContext) LogManager.getContext(false);
                Configuration config = context.getConfiguration();
                LoggerConfig loggerConfig = config.getLoggerConfig(loggerName);
                loggerConfig.setLevel(originalLevel);
                context.updateLoggers();
                log.info("Log level reset: {} -> {}", loggerName, originalLevel);
            }
        });
    }

    public Mono<Void> resetAllLogLevels() {
        return Flux.fromIterable(new ArrayList<>(originalLevels.keySet()))
                .flatMap(this::resetLogLevel)
                .then();
    }

    public Mono<List<String>> getAvailableLevels() {
        return Mono.just(List.of(
                Level.OFF.name(),
                Level.FATAL.name(),
                Level.ERROR.name(),
                Level.WARN.name(),
                Level.INFO.name(),
                Level.DEBUG.name(),
                Level.TRACE.name(),
                Level.ALL.name()
        ));
    }

    public Mono<Void> setRootLogLevel(String level) {
        return setLogLevel(LogManager.ROOT_LOGGER_NAME, level);
    }
}
