package com.logmanager.service.metrics;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
public class BatchProcessor<T, R> {

    private final String name;
    private final int maxBatchSize;
    private final Duration flushInterval;
    private final Duration maxLatency;
    private final Function<List<T>, Mono<List<R>>> batchHandler;
    private final Consumer<List<R>> resultCallback;

    private final Sinks.Many<T> inputSink = Sinks.many().unicast().onBackpressureBuffer();
    private final AtomicBoolean started = new AtomicBoolean(false);

    public BatchProcessor(String name, int maxBatchSize, Duration flushInterval, Duration maxLatency,
                          Function<List<T>, Mono<List<R>>> batchHandler,
                          Consumer<List<R>> resultCallback) {
        this.name = name;
        this.maxBatchSize = maxBatchSize;
        this.flushInterval = flushInterval;
        this.maxLatency = maxLatency;
        this.batchHandler = batchHandler;
        this.resultCallback = resultCallback;
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            log.info("Starting batch processor '{}' with maxSize={}, flushInterval={}, maxLatency={}",
                    name, maxBatchSize, flushInterval, maxLatency);

            inputSink.asFlux()
                    .windowTimeout(maxBatchSize, flushInterval)
                    .flatMap(window -> window.collectList()
                            .filter(list -> !list.isEmpty())
                            .flatMap(batch -> {
                                log.debug("Processing batch of size {} for '{}'", batch.size(), name);
                                return batchHandler.apply(batch)
                                        .doOnNext(results -> {
                                            if (resultCallback != null) {
                                                resultCallback.accept(results);
                                            }
                                        })
                                        .onErrorResume(e -> {
                                            log.error("Batch processing failed for '{}'", name, e);
                                            return Mono.empty();
                                        });
                            }))
                    .subscribe();
        }
    }

    public void submit(T item) {
        Sinks.EmitResult result = inputSink.tryEmitNext(item);
        if (result.isFailure()) {
            log.warn("Failed to submit item to batch processor '{}': {}", name, result);
        }
    }

    public void submitAll(List<T> items) {
        items.forEach(this::submit);
    }

    public void stop() {
        if (started.compareAndSet(true, false)) {
            log.info("Stopping batch processor '{}'", name);
            inputSink.tryEmitComplete();
        }
    }

    public boolean isStarted() {
        return started.get();
    }

    public static <T, R> BatchProcessor<T, R> create(String name, int maxBatchSize, Duration flushInterval,
                                                      Function<List<T>, Mono<List<R>>> handler) {
        return new BatchProcessor<>(name, maxBatchSize, flushInterval, Duration.ofSeconds(30), handler, null);
    }
}
