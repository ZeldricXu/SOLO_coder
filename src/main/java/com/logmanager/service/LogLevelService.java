package com.logmanager.service;

import com.logmanager.common.enums.LogLevel;
import com.logmanager.domain.model.LogLevelConfig;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Map;

public interface LogLevelService {
    Mono<LogLevelConfig> setLogLevel(String serviceName, String loggerName, LogLevel targetLevel, Duration ttl, String reason, String operator);
    Mono<LogLevelConfig> getLogLevelConfig(String id);
    Flux<LogLevelConfig> getLogLevelsByService(String serviceName);
    Flux<LogLevelConfig> getAllActiveLogLevels();
    Mono<Void> resetLogLevel(String id);
    Mono<Void> resetAllLogLevels(String serviceName);
    Mono<Map<String, LogLevel>> getCurrentLogLevels(String serviceName);
    Mono<Boolean> applyLogLevelConfig(LogLevelConfig config);
    Flux<LogLevelConfig> getExpiredConfigs();
    Mono<Void> cleanExpiredConfigs();
}
