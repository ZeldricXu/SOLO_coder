package com.modelguard.util;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@Slf4j
public final class ReactiveBridgeUtil {

    private ReactiveBridgeUtil() {
    }

    public static <T> Mono<T> monoFromCallable(Callable<T> callable) {
        return Mono.fromCallable(callable)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(e -> {
                    log.error("Callable execution failed", e);
                    return e;
                });
    }

    public static <T> Mono<T> monoFromRunnable(Runnable runnable, T result) {
        return Mono.<T>fromCallable(() -> {
            runnable.run();
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public static Mono<Void> monoFromRunnable(Runnable runnable) {
        return Mono.fromRunnable(runnable)
                .subscribeOn(Schedulers.boundedElastic());
    }

    public static <T> Flux<T> fluxFromIterable(Iterable<T> iterable) {
        return Flux.fromIterable(iterable)
                .subscribeOn(Schedulers.boundedElastic());
    }

    public static <T> Mono<List<T>> monoListFromCallable(Callable<List<T>> callable) {
        return monoFromCallable(callable);
    }

    public static <T> Mono<T> supplyAsync(Supplier<T> supplier) {
        return Mono.fromSupplier(supplier)
                .subscribeOn(Schedulers.boundedElastic());
    }

    public static <T> Mono<T> wrapWithErrorHandling(Mono<T> mono, String errorMessage) {
        return mono.onErrorMap(e -> {
            log.error(errorMessage, e);
            return e;
        });
    }
}
