package com.datastandard.modules.logging;

import com.datastandard.modules.logging.dto.LogLevelChangeRequest;
import com.datastandard.modules.logging.dto.LoggerStatusResponse;
import com.datastandard.modules.logging.dto.LogQueryRequest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogLevelManagementService {

    private final LogbackLevelChanger levelChanger;
    private final LoggerConfiguration loggerConfiguration;
    private final AsyncLoggerAppender asyncLoggerAppender;

    private final Map<String, Instant> temporaryLevels = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loggerConfiguration.restorePersistentConfigs(levelChanger)
                .subscribe(
                        restored -> log.info("Restored {} persistent log configurations", restored.size()),
                        error -> log.error("Failed to restore persistent log configurations", error)
                );
    }

    public Mono<LoggerStatusResponse> changeLogLevel(LogLevelChangeRequest request) {
        return Mono.fromCallable(() -> {
            String packagePath = request.getPackagePath();
            String level = request.getLevel().toUpperCase();

            validateLevel(level);

            boolean changed = levelChanger.changeLogLevel(packagePath, level);

            LoggerStatusResponse.LoggerStatusResponseBuilder response = LoggerStatusResponse.builder()
                    .packagePath(packagePath)
                    .currentLevel(levelChanger.getCurrentLevel(packagePath))
                    .effectiveLevel(((LogbackLevelChangerImpl) levelChanger).getEffectiveLevel(packagePath))
                    .configuredLevel(level)
                    .lastModified(Instant.now())
                    .persistent(request.isPersistent());

            if (request.getDurationMinutes() != null && request.getDurationMinutes() > 0) {
                Instant expiresAt = Instant.now().plus(request.getDurationMinutes(), ChronoUnit.MINUTES);
                temporaryLevels.put(packagePath, expiresAt);
                response.expiresAt(expiresAt).isTemporary(true);
            }

            if (request.isPersistent()) {
                loggerConfiguration.saveConfig(packagePath, level, true).subscribe();
                response.persistent(true);
            } else {
                loggerConfiguration.saveConfig(packagePath, level, false).subscribe();
            }

            if (changed) {
                log.info("Log level changed: {} -> {} (persistent: {})", packagePath, level, request.isPersistent());
            }

            return response.build();
        });
    }

    public Mono<LoggerStatusResponse> getLoggerStatus(String packagePath) {
        return Mono.fromCallable(() -> {
            String currentLevel = levelChanger.getCurrentLevel(packagePath);
            String effectiveLevel = ((LogbackLevelChangerImpl) levelChanger).getEffectiveLevel(packagePath);

            LoggerStatusResponse.LoggerStatusResponseBuilder builder = LoggerStatusResponse.builder()
                    .packagePath(packagePath)
                    .currentLevel(currentLevel)
                    .effectiveLevel(effectiveLevel);

            return loggerConfiguration.getConfig(packagePath)
                    .map(config -> {
                        builder.configuredLevel(config.getLevel())
                                .lastModified(config.getConfiguredAt())
                                .persistent(config.isPersistent())
                                .modifiedBy(config.getModifiedBy());

                        Instant expiresAt = temporaryLevels.get(packagePath);
                        if (expiresAt != null && expiresAt.isAfter(Instant.now())) {
                            builder.expiresAt(expiresAt).isTemporary(true);
                        }

                        return builder.build();
                    })
                    .defaultIfEmpty(builder.build())
                    .block();
        });
    }

    public Flux<LoggerStatusResponse> getAllLoggerStatuses(String filter) {
        List<String> loggerNames = levelChanger.getAllLoggerNames();

        return Flux.fromIterable(loggerNames)
                .filter(name -> filter == null || filter.isEmpty() || name.contains(filter))
                .flatMap(this::getLoggerStatus);
    }

    public Mono<LoggerStatusResponse.BatchResponse> getAllLoggerStatusesBatch(String filter) {
        return getAllLoggerStatuses(filter)
                .collectList()
                .map(list -> LoggerStatusResponse.BatchResponse.builder()
                        .loggers(list)
                        .totalCount(list.size())
                        .filter(filter)
                        .build());
    }

    public Mono<Boolean> resetLogLevel(String packagePath) {
        return Mono.fromCallable(() -> {
            levelChanger.resetLogLevel(packagePath);
            temporaryLevels.remove(packagePath);
            loggerConfiguration.removeConfig(packagePath).subscribe();
            log.info("Log level reset for: {}", packagePath);
            return true;
        });
    }

    public Mono<Boolean> resetAllLogLevels() {
        return Mono.fromCallable(() -> {
            levelChanger.resetAllLogLevels();
            temporaryLevels.clear();
            loggerConfiguration.getAllConfigs()
                    .filter(LoggerConfiguration.LogLevelConfig::isPersistent)
                    .flatMap(config -> Mono.fromRunnable(() ->
                            levelChanger.changeLogLevel(config.getPackagePath(), config.getLevel())
                    ))
                    .subscribe();
            log.info("All non-persistent log levels reset");
            return true;
        });
    }

    public Mono<Boolean> cleanupExpiredTemporaryLevels() {
        return Flux.fromIterable(temporaryLevels.entrySet())
                .filter(entry -> entry.getValue().isBefore(Instant.now()))
                .flatMap(entry -> {
                    String packagePath = entry.getKey();
                    return loggerConfiguration.getConfig(packagePath)
                            .flatMap(config -> {
                                if (config.isPersistent()) {
                                    return Mono.fromCallable(() -> {
                                        levelChanger.changeLogLevel(packagePath, config.getLevel());
                                        temporaryLevels.remove(packagePath);
                                        log.info("Restored persistent log level for: {}", packagePath);
                                        return true;
                                    });
                                } else {
                                    return resetLogLevel(packagePath);
                                }
                            });
                })
                .then(Mono.just(true));
    }

    public Flux<Map<String, Object>> queryLogs(LogQueryRequest request) {
        return asyncLoggerAppender.queryLogs(request);
    }

    private void validateLevel(String level) {
        List<String> validLevels = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");
        if (!validLevels.contains(level)) {
            throw new IllegalArgumentException("Invalid log level: " + level +
                    ". Valid levels are: " + validLevels);
        }
    }
}
