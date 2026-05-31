package com.datastandard.modules.slo;

import com.datastandard.modules.slo.calculator.SliCalculatorRegistry;
import com.datastandard.modules.slo.calculator.SliTypeCalculator;
import com.datastandard.modules.slo.dto.SliCalculationRequest;
import com.datastandard.modules.slo.entity.SliMetric;
import com.datastandard.modules.slo.entity.SloDefinition;
import com.datastandard.modules.slo.mapper.SliMetricMapper;
import com.datastandard.modules.slo.mapper.SloMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SliCalculationService {

    private final SloMapper sloMapper;
    private final SliMetricMapper sliMetricMapper;
    private final SliCalculatorRegistry calculatorRegistry;
    private final MeterRegistry meterRegistry;

    private final Counter sliCalculationCounter;
    private final DistributionSummary sliValueSummary;

    public SliCalculationService(SloMapper sloMapper,
                                 SliMetricMapper sliMetricMapper,
                                 SliCalculatorRegistry calculatorRegistry,
                                 MeterRegistry meterRegistry) {
        this.sloMapper = sloMapper;
        this.sliMetricMapper = sliMetricMapper;
        this.calculatorRegistry = calculatorRegistry;
        this.meterRegistry = meterRegistry;

        this.sliCalculationCounter = Counter.builder("sli.calculation.total")
                .description("SLI计算总次数")
                .register(meterRegistry);
        this.sliValueSummary = DistributionSummary.builder("sli.value.distribution")
                .description("SLI值分布")
                .register(meterRegistry);
    }

    public Mono<Double> calculateSli(SliCalculationRequest request) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                SloDefinition slo = loadEnabledSlo(request.getSloId());
                SliTypeCalculator calculator = calculatorRegistry.getCalculator(slo.getSliType());

                sliCalculationCounter.increment();
                double sliValue = calculator.calculate(request, slo);

                sliValueSummary.record(sliValue);
                saveSliMetric(slo, request, sliValue);

                log.debug("SLI计算完成: sloId={}, type={}, value={}", request.getSloId(), slo.getSliType(), sliValue);
                return sliValue;
            } finally {
                sample.stop(meterRegistry.timer("sli.calculation.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<SliMetric> calculateSlidingWindowSli(SliCalculationRequest request) {
        return Mono.fromCallable(() -> {
            SloDefinition slo = loadEnabledSlo(request.getSloId());
            SliTypeCalculator calculator = calculatorRegistry.getCalculator(slo.getSliType());

            long windowSize = resolveWindowSize(request, slo);
            long slideInterval = resolveSlideInterval(request);

            return computeWindowMetrics(request, slo, calculator, windowSize, slideInterval);
        }).flatMapMany(Flux::fromIterable)
                .flatMap(metric -> persistMetric(metric));
    }

    private List<SliMetric> computeWindowMetrics(SliCalculationRequest request,
                                                  SloDefinition slo,
                                                  SliTypeCalculator calculator,
                                                  long windowSize,
                                                  long slideInterval) {
        List<SliMetric> metrics = new ArrayList<>();
        Instant currentStart = request.getStartTime();
        Instant endTime = request.getEndTime();

        while (isWindowWithinRange(currentStart, windowSize, endTime)) {
            Instant windowEnd = currentStart.plusSeconds(windowSize);
            SliCalculationRequest windowRequest = buildWindowRequest(request, currentStart, windowEnd);
            double sliValue = calculator.calculate(windowRequest, slo);
            metrics.add(buildSliMetric(slo, windowRequest, sliValue));
            currentStart = currentStart.plusSeconds(slideInterval);
        }

        return metrics;
    }

    private Mono<SliMetric> persistMetric(SliMetric metric) {
        return Mono.fromCallable(() -> {
            sliMetricMapper.insert(metric);
            return metric;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<SliMetric>> getSliHistory(String sloId, Instant startTime, Instant endTime) {
        return Mono.fromCallable(() ->
                sliMetricMapper.findBySloIdAndTimeRange(sloId, startTime, endTime)
        ).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<SliMetric> getLatestSli(String sloId) {
        return Mono.fromCallable(() ->
                sliMetricMapper.findLatestBySloId(sloId)
                        .orElseThrow(() -> new IllegalArgumentException("未找到SLI指标: " + sloId))
        ).subscribeOn(Schedulers.boundedElastic());
    }

    private SloDefinition loadEnabledSlo(String sloId) {
        SloDefinition slo = sloMapper.findById(sloId)
                .orElseThrow(() -> new IllegalArgumentException("SLO不存在: " + sloId));
        if (!slo.isEnabled()) {
            throw new IllegalStateException("SLO已禁用: " + sloId);
        }
        return slo;
    }

    private long resolveWindowSize(SliCalculationRequest request, SloDefinition slo) {
        if (request.getWindowSizeSeconds() != null) {
            return request.getWindowSizeSeconds();
        }
        return slo.getTimeWindowSeconds() != null ? slo.getTimeWindowSeconds() : 3600;
    }

    private long resolveSlideInterval(SliCalculationRequest request) {
        return request.getSlideIntervalSeconds() != null ? request.getSlideIntervalSeconds() : 300;
    }

    private boolean isWindowWithinRange(Instant currentStart, long windowSize, Instant endTime) {
        Instant windowEnd = currentStart.plusSeconds(windowSize);
        return windowEnd.isBefore(endTime) || windowEnd.equals(endTime);
    }

    private SliCalculationRequest buildWindowRequest(SliCalculationRequest original,
                                                      Instant windowStart,
                                                      Instant windowEnd) {
        return SliCalculationRequest.builder()
                .sloId(original.getSloId())
                .startTime(windowStart)
                .endTime(windowEnd)
                .metricName(original.getMetricName())
                .filters(original.getFilters())
                .aggregation(original.getAggregation())
                .dataPoints(filterDataPoints(original.getDataPoints(), windowStart, windowEnd))
                .build();
    }

    private List<SliCalculationRequest.DataPoint> filterDataPoints(
            List<SliCalculationRequest.DataPoint> dataPoints, Instant start, Instant end) {
        if (dataPoints == null) {
            return List.of();
        }
        return dataPoints.stream()
                .filter(dp -> !dp.getTimestamp().isBefore(start) && dp.getTimestamp().isBefore(end))
                .collect(Collectors.toList());
    }

    private void saveSliMetric(SloDefinition slo, SliCalculationRequest request, double sliValue) {
        try {
            sliMetricMapper.insert(buildSliMetric(slo, request, sliValue));
        } catch (Exception e) {
            log.error("保存SLI指标失败: sloId={}", slo.getSloId(), e);
        }
    }

    private SliMetric buildSliMetric(SloDefinition slo, SliCalculationRequest request, double sliValue) {
        var dataPoints = request.getDataPoints();
        long totalEvents = dataPoints != null ? dataPoints.size() : 0;
        long goodEvents = dataPoints != null
                ? dataPoints.stream().filter(SliCalculationRequest.DataPoint::isSuccess).count()
                : 0;
        long badEvents = totalEvents - goodEvents;

        return SliMetric.builder()
                .metricId(UUID.randomUUID().toString())
                .sloId(slo.getSloId())
                .sliType(slo.getSliType())
                .windowStart(request.getStartTime())
                .windowEnd(request.getEndTime())
                .sliValue(sliValue)
                .totalEvents(totalEvents)
                .goodEvents(goodEvents)
                .badEvents(badEvents)
                .aggregation(request.getAggregation())
                .createdAt(Instant.now())
                .deleted(0)
                .build();
    }
}
