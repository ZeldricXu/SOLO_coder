package com.chain.infrastructure.gasestimator.batcher;

import com.chain.infrastructure.gasestimator.dto.GasEstimateRequest;
import com.chain.infrastructure.gasestimator.dto.GasEstimateResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class RequestBatcher {

    private final int maxBatchSize;
    private final Duration maxWaitTime;
    private final List<PendingRequest> pendingRequests = new CopyOnWriteArrayList<>();
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    public RequestBatcher(int maxBatchSize, Duration maxWaitTime) {
        this.maxBatchSize = maxBatchSize;
        this.maxWaitTime = maxWaitTime;
    }

    public Mono<GasEstimateResult> submit(GasEstimateRequest request,
                                           java.util.function.Function<List<GasEstimateRequest>, Mono<Map<GasEstimateRequest, GasEstimateResult>>> batchProcessor) {
        PendingRequest pending = new PendingRequest(request);
        pendingRequests.add(pending);

        checkFlush(batchProcessor);

        return pending.resultMono;
    }

    private void checkFlush(java.util.function.Function<List<GasEstimateRequest>, Mono<Map<GasEstimateRequest, GasEstimateResult>>> batchProcessor) {
        if (pendingRequests.size() >= maxBatchSize && flushing.compareAndSet(false, true)) {
            flush(batchProcessor);
        }
    }

    private void flush(java.util.function.Function<List<GasEstimateRequest>, Mono<Map<GasEstimateRequest, GasEstimateResult>>> batchProcessor) {
        if (pendingRequests.isEmpty()) {
            flushing.set(false);
            return;
        }

        List<PendingRequest> batch = new CopyOnWriteArrayList<>(pendingRequests);
        pendingRequests.clear();
        flushing.set(false);

        List<GasEstimateRequest> requests = batch.stream()
                .map(pr -> pr.request)
                .toList();

        batchProcessor.apply(requests)
                .doOnNext(results -> batch.forEach(pr -> {
                    GasEstimateResult result = results.get(pr.request);
                    if (result != null) {
                        pr.resultSink.success(result);
                    } else {
                        pr.resultSink.error(new RuntimeException("No result for request"));
                    }
                }))
                .doOnError(error -> batch.forEach(pr -> pr.resultSink.error(error)))
                .subscribe();
    }

    public void startAutoFlush(java.util.function.Function<List<GasEstimateRequest>, Mono<Map<GasEstimateRequest, GasEstimateResult>>> batchProcessor) {
        Flux.interval(maxWaitTime)
                .doOnNext(tick -> {
                    if (!pendingRequests.isEmpty() && flushing.compareAndSet(false, true)) {
                        flush(batchProcessor);
                    }
                })
                .subscribe();
    }

    private static class PendingRequest {
        final GasEstimateRequest request;
        final reactor.core.publisher.Sinks.One<GasEstimateResult> resultSink = reactor.core.publisher.Sinks.one();
        final Mono<GasEstimateResult> resultMono = resultSink.asMono();

        PendingRequest(GasEstimateRequest request) {
            this.request = request;
        }
    }
}
