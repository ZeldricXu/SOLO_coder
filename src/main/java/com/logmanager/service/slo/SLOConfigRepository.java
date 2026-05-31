package com.logmanager.service.slo;

import com.logmanager.domain.model.SLOConfig;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SLOConfigRepository {
    Mono<SLOConfig> save(SLOConfig slo);

    Mono<SLOConfig> findById(String sloId);

    Flux<SLOConfig> findByServiceName(String serviceName);

    Flux<SLOConfig> findAll();

    Mono<Void> deleteById(String sloId);

    Mono<Boolean> existsById(String sloId);
}
