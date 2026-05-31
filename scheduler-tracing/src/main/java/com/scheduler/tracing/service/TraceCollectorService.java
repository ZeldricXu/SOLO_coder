package com.scheduler.tracing.service;

import com.scheduler.persistence.entity.TraceSpan;
import com.scheduler.persistence.mapper.TraceSpanMapper;
import com.scheduler.tracing.sampling.SamplingStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TraceCollectorService {

    private final List<SamplingStrategy> samplingStrategies;
    private final TraceSpanMapper spanMapper;
    private final MeterRegistry meterRegistry;

    private Counter receivedSpans;
    private Counter sampledSpans;
    private Counter rejectedSpans;

    public void initMetrics() {
        receivedSpans = meterRegistry.counter("tracing.spans.received");
        sampledSpans = meterRegistry.counter("tracing.spans.sampled");
        rejectedSpans = meterRegistry.counter("tracing.spans.rejected");
    }

    public Mono<TraceSpan> collect(TraceSpan span) {
        if (receivedSpans == null) initMetrics();
        receivedSpans.increment();

        return Mono.fromCallable(() -> {
            if (span.getSpanId() == null) {
                span.setSpanId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            }
            if (span.getStartTime() == null) {
                span.setStartTime(Instant.now());
            }
            if (span.getEndTime() != null && span.getDurationMicros() == null) {
                span.setDurationMicros(java.time.Duration.between(span.getStartTime(), span.getEndTime()).toNanos() / 1000);
            }

            boolean sampled = samplingStrategies.stream()
                    .anyMatch(strategy -> strategy.shouldSample(span));
            span.setSampled(sampled);

            if (sampled) {
                sampledSpans.increment();
                spanMapper.insert(span);
                log.debug("Sampled and stored span: {} for trace: {}", span.getSpanId(), span.getTraceId());
            } else {
                rejectedSpans.increment();
                log.debug("Rejected span: {} for trace: {}", span.getSpanId(), span.getTraceId());
            }

            return span;
        });
    }

    public Flux<TraceSpan> collectBatch(Flux<TraceSpan> spans) {
        return spans.flatMap(this::collect, 50);
    }

    public List<TraceSpan> getTrace(String traceId) {
        return spanMapper.findByTraceId(traceId);
    }

    public List<TraceSpan> getServiceSpans(String serviceName, Instant start, Instant end) {
        return spanMapper.findByServiceNameAndTimeRange(serviceName, start, end);
    }

    public List<String> getActiveServices(Instant since) {
        return spanMapper.findDistinctServices(since);
    }

    public List<String> getSamplingStrategies() {
        return samplingStrategies.stream().map(SamplingStrategy::getName).toList();
    }

    public void updateSamplerConfig(String strategyName, Object config) {
        samplingStrategies.stream()
                .filter(s -> s.getName().equalsIgnoreCase(strategyName))
                .findFirst()
                .ifPresent(s -> s.updateConfig(config));
    }
}
