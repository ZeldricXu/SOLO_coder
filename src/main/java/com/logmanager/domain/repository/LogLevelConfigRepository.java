package com.logmanager.domain.repository;

import com.logmanager.domain.model.LogLevelConfig;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface LogLevelConfigRepository {
    Mono<LogLevelConfig> save(LogLevelConfig config);
    Mono<LogLevelConfig> findById(String id);
    Flux<LogLevelConfig> findByServiceName(String serviceName);
    Flux<LogLevelConfig> findActiveByServiceName(String serviceName);
    Mono<Void> deleteById(String id);
    Flux<LogLevelConfig> findAllActive();
}
