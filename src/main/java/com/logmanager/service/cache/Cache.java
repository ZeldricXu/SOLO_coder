package com.logmanager.service.cache;

import reactor.core.publisher.Mono;
import java.time.Duration;

public interface Cache<K, V> {
    String getName();

    Mono<V> get(K key);

    Mono<Void> put(K key, V value);

    Mono<Void> put(K key, V value, Duration ttl);

    Mono<Void> invalidate(K key);

    Mono<Void> invalidateAll();

    Mono<Boolean> contains(K key);

    Mono<Long> size();

    default Mono<V> getOrLoad(K key, java.util.function.Supplier<Mono<V>> loader) {
        return get(key)
                .switchIfEmpty(Mono.defer(() ->
                        loader.get()
                                .flatMap(value -> put(key, value).then(Mono.just(value)))
                ));
    }
}
