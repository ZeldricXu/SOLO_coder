package com.scheduler.anomaly.detection.batch;

import com.scheduler.anomaly.detection.AnomalyResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class RequestBatcher {

    private final Sinks.Many<BatchDetectionRequest> requestSink = Sinks.many().multicast().onBackpressureBuffer();
    private final Map<String, Sinks.One<List<AnomalyResult>>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicLong batchCounter = new AtomicLong(0);

    private static final int MAX_BATCH_SIZE = 100;
    private static final Duration BATCH_TIMEOUT = Duration.ofMillis(500);

    @PostConstruct
    public void init() {
        requestSink.asFlux()
                .windowTimeout(MAX_BATCH_SIZE, BATCH_TIMEOUT)
                .concatMap(this::processBatch)
                .subscribe(
                        batchResult -> log.debug("Processed batch: {}", batchResult.getBatchId()),
                        error -> log.error("Batch processing error", error)
                );
        log.info("Request batcher initialized with maxSize={}, timeout={}", MAX_BATCH_SIZE, BATCH_TIMEOUT);
    }

    @PreDestroy
    public void shutdown() {
        requestSink.tryEmitComplete();
        log.info("Request batcher shutdown");
    }

    public Mono<List<AnomalyResult>> submit(BatchDetectionRequest request) {
        String requestId = UUID.randomUUID().toString();
        Sinks.One<List<AnomalyResult>> resultSink = Sinks.one();
        pendingRequests.put(requestId, resultSink);

        requestSink.tryEmitNext(request);

        return resultSink.asMono()
                .doFinally(signalType -> pendingRequests.remove(requestId));
    }

    private Mono<BatchDetectionResult> processBatch(Flux<BatchDetectionRequest> batch) {
        return batch.collectList()
                .flatMap(requests -> {
                    if (requests.isEmpty()) {
                        return Mono.empty();
                    }

                    String batchId = "batch_" + batchCounter.incrementAndGet();
                    long startTime = System.currentTimeMillis();
                    log.debug("Processing batch {} with {} requests", batchId, requests.size());

                    List<AnomalyResult> allResults = new ArrayList<>();
                    Map<String, Integer> algorithmStats = new HashMap<>();

                    for (BatchDetectionRequest request : requests) {
                        List<AnomalyResult> results = processRequest(request);
                        allResults.addAll(results);

                        for (AnomalyResult result : results) {
                            algorithmStats.merge(result.getAlgorithm(), 1, Integer::sum);
                        }
                    }

                    long processingTime = System.currentTimeMillis() - startTime;
                    int anomalyCount = (int) allResults.stream().filter(AnomalyResult::isAnomaly).count();

                    BatchDetectionResult batchResult = BatchDetectionResult.builder()
                            .batchId(batchId)
                            .totalRequests(requests.size())
                            .anomalyCount(anomalyCount)
                            .results(allResults)
                            .algorithmStats(algorithmStats)
                            .processingTimeMs(processingTime)
                            .success(true)
                            .build();

                    return Mono.just(batchResult);
                });
    }

    private List<AnomalyResult> processRequest(BatchDetectionRequest request) {
        List<AnomalyResult> results = new ArrayList<>();
        for (String algorithm : request.getAlgorithms()) {
            try {
                AnomalyResult result = simulateDetection(request, algorithm);
                results.add(result);
            } catch (Exception e) {
                log.warn("Error processing detection for algorithm {}", algorithm, e);
            }
        }
        return results;
    }

    private AnomalyResult simulateDetection(BatchDetectionRequest request, String algorithm) {
        return AnomalyResult.normal(request.getMetricName(), request.getCurrentValue(), algorithm);
    }

    public int getPendingRequestCount() {
        return pendingRequests.size();
    }
}
