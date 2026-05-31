package com.monitoring.trace.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.monitoring.trace.cache.ConcurrentSpanBuffer;
import com.monitoring.trace.model.TraceSpan;
import com.monitoring.trace.sampling.SamplingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TraceCollectorService {

    private static final int TRACE_BUFFER_CAPACITY = 1024;
    private static final int MAX_BUFFERED_TRACES = 10000;
    private static final int MAX_SAMPLED_TRACES = 50000;
    private static final Duration BUFFER_EXPIRE_AFTER_WRITE = Duration.ofMinutes(5);
    private static final Duration SAMPLED_EXPIRE_AFTER_WRITE = Duration.ofHours(1);
    private static final double DEFAULT_SAMPLING_RATE = 0.1;
    private static final double DEFAULT_ERROR_THRESHOLD = 0.05;
    private static final long DEFAULT_LATENCY_THRESHOLD_MS = 5000L;
    private static final boolean DEFAULT_TAIL_SAMPLING_ENABLED = true;

    private final Map<String, SamplingStrategy> strategies = new ConcurrentHashMap<>();

    private final Cache<String, ConcurrentSpanBuffer> traceBuffer = Caffeine.newBuilder()
            .expireAfterWrite(BUFFER_EXPIRE_AFTER_WRITE)
            .maximumSize(MAX_BUFFERED_TRACES)
            .build();

    private final Cache<String, Boolean> traceSampled = Caffeine.newBuilder()
            .expireAfterWrite(SAMPLED_EXPIRE_AFTER_WRITE)
            .maximumSize(MAX_SAMPLED_TRACES)
            .build();

    private volatile SamplingStrategy.SamplingContext defaultContext = createDefaultContext();

    public void registerStrategy(SamplingStrategy strategy) {
        strategies.put(strategy.getName(), strategy);
    }

    public Mono<Boolean> collectSpan(TraceSpan span) {
        return Mono.fromSupplier(() -> processSpan(span));
    }

    public Flux<Boolean> collectSpans(List<TraceSpan> spans) {
        return Flux.fromIterable(spans)
                .flatMap(this::collectSpan);
    }

    public Mono<List<TraceSpan>> getTrace(String traceId) {
        return Mono.fromSupplier(() -> getTraceSpans(traceId));
    }

    public Set<String> getAvailableStrategies() {
        return strategies.keySet();
    }

    public void updateSamplingConfig(double samplingRate, double errorThreshold,
                                     long latencyThresholdMs, boolean tailSamplingEnabled) {
        this.defaultContext = new SamplingStrategy.SamplingContext(
                samplingRate, errorThreshold, latencyThresholdMs, tailSamplingEnabled
        );
        log.info("Updated sampling config: rate={}, errorThreshold={}, latencyThreshold={}, tailSampling={}",
                samplingRate, errorThreshold, latencyThresholdMs, tailSamplingEnabled);
    }

    public Mono<Long> getBufferedTraceCount() {
        return Mono.fromSupplier(traceBuffer::estimatedSize);
    }

    public void cleanOldTraces() {
        traceBuffer.cleanUp();
        traceSampled.cleanUp();
    }

    private boolean processSpan(TraceSpan span) {
        if (!isValidSpan(span)) {
            return false;
        }

        String traceId = span.getTraceId();
        Boolean alreadySampled = traceSampled.getIfPresent(traceId);

        if (alreadySampled != null && alreadySampled) {
            bufferSpan(traceId, span);
            return true;
        }

        SamplingStrategy.SamplingContext context = getSamplingContext();
        boolean shouldSample = evaluateSamplingStrategies(span, context);

        if (shouldSample) {
            markAsSampled(traceId);
            bufferSpan(traceId, span);
            logSampledSpan(span, traceId);
        } else if (context.tailSamplingEnabled()) {
            bufferSpan(traceId, span);
            evaluateTailSampling(traceId, context);
        }

        return shouldSample;
    }

    private static boolean isValidSpan(TraceSpan span) {
        return span.getTraceId() != null && span.getSpanId() != null;
    }

    private SamplingStrategy.SamplingContext getSamplingContext() {
        return defaultContext;
    }

    private boolean evaluateSamplingStrategies(TraceSpan span, SamplingStrategy.SamplingContext context) {
        for (SamplingStrategy strategy : strategies.values()) {
            if (strategy.shouldSample(span, context)) {
                return true;
            }
        }
        return false;
    }

    private void evaluateTailSampling(String traceId, SamplingStrategy.SamplingContext context) {
        ConcurrentSpanBuffer buffer = traceBuffer.getIfPresent(traceId);
        if (buffer == null || buffer.isEmpty()) {
            return;
        }

        List<TraceSpan> spans = buffer.getSnapshot();
        for (SamplingStrategy strategy : strategies.values()) {
            if (strategy.shouldSampleTail(traceId, spans, context)) {
                markAsSampled(traceId);
                log.info("Tail sampled trace: traceId={}, spans={}", traceId, spans.size());
                return;
            }
        }
    }

    private void bufferSpan(String traceId, TraceSpan span) {
        traceBuffer.asMap().compute(traceId, (key, existing) -> {
            ConcurrentSpanBuffer buffer = existing != null ? existing : new ConcurrentSpanBuffer(TRACE_BUFFER_CAPACITY);
            buffer.add(span);
            return buffer;
        });
    }

    private void markAsSampled(String traceId) {
        traceSampled.put(traceId, true);
    }

    private List<TraceSpan> getTraceSpans(String traceId) {
        ConcurrentSpanBuffer buffer = traceBuffer.getIfPresent(traceId);
        if (buffer == null) {
            return Collections.emptyList();
        }
        return buffer.getImmutableSnapshot();
    }

    private static SamplingStrategy.SamplingContext createDefaultContext() {
        return new SamplingStrategy.SamplingContext(
                DEFAULT_SAMPLING_RATE,
                DEFAULT_ERROR_THRESHOLD,
                DEFAULT_LATENCY_THRESHOLD_MS,
                DEFAULT_TAIL_SAMPLING_ENABLED
        );
    }

    private static void logSampledSpan(TraceSpan span, String traceId) {
        log.debug("Sampled span: traceId={}, spanId={}, service={}",
                traceId, span.getSpanId(), span.getServiceName());
    }
}
