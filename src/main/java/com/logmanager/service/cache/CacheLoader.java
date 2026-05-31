package com.logmanager.service.cache;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;

@FunctionalInterface
public interface CacheLoader<K, V> {
    Mono<Map<K, V>> loadAll();

    default Flux<Map.Entry<K, V>> loadStream() {
        return loadAll().flatMapMany(map -> Flux.fromIterable(map.entrySet()));
    }
}
