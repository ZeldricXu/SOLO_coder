package com.logmanager.service.slo.calculator;

import com.logmanager.domain.model.SLOConfig;
import com.logmanager.service.slo.SLOConfigRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemorySLOConfigRepository implements SLOConfigRepository {

    private final Map<String, SLOConfig> sloStore = new ConcurrentHashMap<>();

    @Override
    public Mono<SLOConfig> save(SLOConfig slo) {
        sloStore.put(slo.getSloId(), slo);
        return Mono.just(slo);
    }

    @Override
    public Mono<SLOConfig> findById(String sloId) {
        SLOConfig slo = sloStore.get(sloId);
        return slo != null ? Mono.just(slo) : Mono.empty();
    }

    @Override
    public Flux<SLOConfig> findByServiceName(String serviceName) {
        return Flux.fromIterable(sloStore.values())
                .filter(slo -> serviceName.equals(slo.getServiceName()));
    }

    @Override
    public Flux<SLOConfig> findAll() {
        return Flux.fromIterable(sloStore.values());
    }

    @Override
    public Mono<Void> deleteById(String sloId) {
        sloStore.remove(sloId);
        return Mono.empty();
    }

    @Override
    public Mono<Boolean> existsById(String sloId) {
        return Mono.just(sloStore.containsKey(sloId));
    }
}
