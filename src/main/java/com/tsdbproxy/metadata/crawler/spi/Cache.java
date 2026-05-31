package com.tsdbproxy.metadata.crawler.spi;

import reactor.core.publisher.Mono;

import java.time.Duration;

public interface Cache<K, V> {

    Mono<V> get(K key);

    Mono<Void> put(K key, V value, Duration ttl);

    Mono<Void> invalidate(K key);

    Mono<Void> invalidateAll();

    String getCacheName();
}
