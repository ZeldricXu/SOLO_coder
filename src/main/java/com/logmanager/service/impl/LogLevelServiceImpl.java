package com.logmanager.service.impl;

import com.logmanager.common.enums.LogLevel;
import com.logmanager.domain.event.DomainEvent;
import com.logmanager.domain.event.EventPublisher;
import com.logmanager.domain.model.LogLevelConfig;
import com.logmanager.domain.repository.LogLevelConfigRepository;
import com.logmanager.service.LogLevelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogLevelServiceImpl implements LogLevelService {

    private final LogLevelConfigRepository logLevelConfigRepository;
    private final EventPublisher eventPublisher;

    private final Map<String, LogLevel> currentLogLevels = new ConcurrentHashMap<>();

    @Override
    public Mono<LogLevelConfig> setLogLevel(String serviceName, String loggerName, LogLevel targetLevel, Duration ttl, String reason, String operator) {
        LogLevelConfig config = new LogLevelConfig();
        config.setId(UUID.randomUUID().toString());
        config.setServiceName(serviceName);
        config.setLoggerName(loggerName);
        config.setCurrentLevel(getCurrentLevel(serviceName, loggerName));
        config.setTargetLevel(targetLevel);
        config.setEffectiveAt(Instant.now());
        config.setExpiresAt(ttl != null ? Instant.now().plus(ttl) : null);
        config.setReason(reason);
        config.setOperator(operator);
        config.setActive(true);
        config.setCreatedAt(Instant.now());
        config.setUpdatedAt(Instant.now());

        return logLevelConfigRepository.save(config)
                .doOnSuccess(saved -> {
                    applyLogLevelConfig(saved);
                    log.info("Log level set: {} -> {} for service: {}, logger: {}", saved.getCurrentLevel(), targetLevel, serviceName, loggerName);
                    eventPublisher.publish(new DomainEvent("log_level.changed", saved.getId(), "log_level"));
                });
    }

    @Override
    public Mono<LogLevelConfig> getLogLevelConfig(String id) {
        return logLevelConfigRepository.findById(id);
    }

    @Override
    public Flux<LogLevelConfig> getLogLevelsByService(String serviceName) {
        return logLevelConfigRepository.findByServiceName(serviceName);
    }

    @Override
    public Flux<LogLevelConfig> getAllActiveLogLevels() {
        return logLevelConfigRepository.findAllActive();
    }

    @Override
    public Mono<Void> resetLogLevel(String id) {
        return logLevelConfigRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Log level config not found: " + id)))
                .flatMap(config -> {
                    config.setActive(false);
                    config.setUpdatedAt(Instant.now());
                    return logLevelConfigRepository.save(config);
                })
                .doOnSuccess(config -> {
                    String key = config.getServiceName() + ":" + config.getLoggerName();
                    currentLogLevels.remove(key);
                    eventPublisher.publish(new DomainEvent("log_level.reset", id, "log_level"));
                })
                .then();
    }

    @Override
    public Mono<Void> resetAllLogLevels(String serviceName) {
        return logLevelConfigRepository.findActiveByServiceName(serviceName)
                .flatMap(config -> {
                    config.setActive(false);
                    config.setUpdatedAt(Instant.now());
                    return logLevelConfigRepository.save(config);
                })
                .doOnNext(config -> {
                    String key = config.getServiceName() + ":" + config.getLoggerName();
                    currentLogLevels.remove(key);
                })
                .then();
    }

    @Override
    public Mono<Map<String, LogLevel>> getCurrentLogLevels(String serviceName) {
        return logLevelConfigRepository.findActiveByServiceName(serviceName)
                .collectMap(
                        LogLevelConfig::getLoggerName,
                        LogLevelConfig::getTargetLevel
                );
    }

    @Override
    public Mono<Boolean> applyLogLevelConfig(LogLevelConfig config) {
        String key = config.getServiceName() + ":" + config.getLoggerName();
        currentLogLevels.put(key, config.getTargetLevel());
        log.info("Applied log level config: {} = {}", key, config.getTargetLevel());
        return Mono.just(true);
    }

    @Override
    public Flux<LogLevelConfig> getExpiredConfigs() {
        Instant now = Instant.now();
        return logLevelConfigRepository.findAllActive()
                .filter(config -> config.getExpiresAt() != null && config.getExpiresAt().isBefore(now));
    }

    @Override
    public Mono<Void> cleanExpiredConfigs() {
        return getExpiredConfigs()
                .flatMap(config -> resetLogLevel(config.getId()))
                .then();
    }

    private LogLevel getCurrentLevel(String serviceName, String loggerName) {
        String key = serviceName + ":" + loggerName;
        return currentLogLevels.getOrDefault(key, LogLevel.INFO);
    }
}
