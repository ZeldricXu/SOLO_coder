package com.chain.infrastructure.txbuilder.cache;

import reactor.core.publisher.Mono;

public interface MultilevelCache<K, V> {

    Mono<V> get(K key);

    Mono<V> put(K key, V value);

    Mono<Void> evict(K key);

    Mono<Void> evictAll();

    String getName();
}
