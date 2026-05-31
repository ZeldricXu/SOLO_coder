package com.datastandard.modules.slo;

import com.datastandard.modules.slo.budget.BudgetCalculator;
import com.datastandard.modules.slo.dto.ErrorBudgetResponse;
import com.datastandard.modules.slo.entity.ErrorBudgetRecord;
import com.datastandard.modules.slo.entity.SliMetric;
import com.datastandard.modules.slo.entity.SloDefinition;
import com.datastandard.modules.slo.mapper.ErrorBudgetRecordMapper;
import com.datastandard.modules.slo.mapper.SliMetricMapper;
import com.datastandard.modules.slo.mapper.SloMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ErrorBudgetService {

    private final SloMapper sloMapper;
    private final SliMetricMapper sliMetricMapper;
    private final ErrorBudgetRecordMapper errorBudgetRecordMapper;
    private final BudgetCalculator budgetCalculator;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final Counter budgetConsumptionCounter;
    private final Counter budgetExhaustedCounter;
    private final Counter budgetRecalculationCounter;
    private final Map<String, Gauge> remainingBudgetGauges = new ConcurrentHashMap<>();

    public ErrorBudgetService(SloMapper sloMapper,
                              SliMetricMapper sliMetricMapper,
                              ErrorBudgetRecordMapper errorBudgetRecordMapper,
                              BudgetCalculator budgetCalculator,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry) {
        this.sloMapper = sloMapper;
        this.sliMetricMapper = sliMetricMapper;
        this.errorBudgetRecordMapper = errorBudgetRecordMapper;
        this.budgetCalculator = budgetCalculator;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;

        this.budgetConsumptionCounter = Counter.builder("error.budget.consumption")
                .description("错误预算消耗次数")
                .register(meterRegistry);
        this.budgetExhaustedCounter = Counter.builder("error.budget.exhausted")
                .description("错误预算耗尽次数")
                .register(meterRegistry);
        this.budgetRecalculationCounter = Counter.builder("error.budget.recalculation")
                .description("错误预算重新计算次数")
                .register(meterRegistry);
    }

    public Mono<ErrorBudgetResponse> calculateErrorBudget(String sloId) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                SloDefinition slo = loadEnabledSlo(sloId);
                Instant now = Instant.now();
                Duration windowDuration = resolveWindowDuration(slo);
                Instant windowStart = now.minus(windowDuration);

                List<SliMetric> sliMetrics = sliMetricMapper.findBySloIdAndTimeRange(sloId, windowStart, now);

                BudgetResult result = computeBudget(slo, sliMetrics, windowStart, now, windowDuration);

                ErrorBudgetResponse response = buildResponse(slo, result, windowStart, now, windowDuration);

                handleBudgetExhaustion(sloId, result);
                saveBudgetRecord(slo, response);
                updateBudgetGauge(slo, result.remainingBudgetPercent());

                budgetRecalculationCounter.increment();
                if (result.consumedBudget() > 0) {
                    budgetConsumptionCounter.increment();
                }

                log.debug("错误预算计算完成: sloId={}, remaining={}%, burnRate={}",
                        sloId, String.format("%.2f", result.remainingBudgetPercent()), String.format("%.2f", result.burnRate()));

                return response;
            } finally {
                sample.stop(meterRegistry.timer("error.budget.calculation.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<ErrorBudgetResponse> calculateSlidingWindowBudget(String sloId, long windowSizeSeconds,
                                                                   long slideIntervalSeconds) {
        return Mono.fromCallable(() -> {
            SloDefinition slo = loadEnabledSlo(sloId);
            Instant now = Instant.now();
            Instant windowStart = now.minusSeconds(slo.getTimeWindowSeconds() != null ?
                    slo.getTimeWindowSeconds() : 2592000);

            List<ErrorBudgetResponse> responses = new ArrayList<>();
            Instant currentStart = windowStart;

            while (isWithinRange(currentStart, windowSizeSeconds, now)) {
                Instant windowEnd = currentStart.plusSeconds(windowSizeSeconds);
                List<SliMetric> sliMetrics = sliMetricMapper.findBySloIdAndTimeRange(sloId, currentStart, windowEnd);

                BudgetResult result = computeBudget(slo, sliMetrics, currentStart, windowEnd,
                        Duration.ofSeconds(windowSizeSeconds));

                responses.add(buildWindowResponse(slo, result, currentStart, windowEnd, windowSizeSeconds));
                currentStart = currentStart.plusSeconds(slideIntervalSeconds);
            }

            return responses;
        }).flatMapMany(Flux::fromIterable);
    }

    public Mono<ErrorBudgetResponse> getLatestBudget(String sloId) {
        return Mono.fromCallable(() -> {
            ErrorBudgetRecord latest = errorBudgetRecordMapper.findLatestBySloId(sloId)
                    .orElseThrow(() -> new IllegalArgumentException("未找到错误预算记录: " + sloId));

            return ErrorBudgetResponse.builder()
                    .sloId(latest.getSloId())
                    .totalBudget(latest.getTotalBudget())
                    .consumedBudget(latest.getConsumedBudget())
                    .remainingBudget(latest.getRemainingBudget())
                    .remainingBudgetPercent((latest.getRemainingBudget() / latest.getTotalBudget()) * 100)
                    .burnRate(latest.getBurnRate())
                    .currentSliValue(latest.getCurrentSliValue())
                    .windowStart(latest.getWindowStart())
                    .windowEnd(latest.getWindowEnd())
                    .budgetStatus(latest.getBudgetStatus())
                    .estimatedExhaustionTime(latest.getEstimatedExhaustionTime())
                    .build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<ErrorBudgetResponse> getBudgetHistory(String sloId, Instant startTime, Instant endTime) {
        return Flux.fromIterable(() -> {
            List<ErrorBudgetRecord> records = errorBudgetRecordMapper.findBySloIdAndTimeRange(sloId, startTime, endTime);
            return records.stream()
                    .map(this::mapRecordToResponse)
                    .iterator();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private BudgetResult computeBudget(SloDefinition slo, List<SliMetric> sliMetrics,
                                        Instant windowStart, Instant windowEnd, Duration windowDuration) {
        double totalBudget = 1.0 - slo.getTargetValue();
        double currentSliValue = budgetCalculator.calculateAverageSli(sliMetrics);
        double consumedBudget = budgetCalculator.calculateConsumedBudget(slo, sliMetrics, windowStart, windowEnd);
        double remainingBudget = totalBudget - consumedBudget;
        double remainingBudgetPercent = (remainingBudget / totalBudget) * 100;
        double burnRate = budgetCalculator.calculateBurnRate(consumedBudget, windowDuration);
        String budgetStatus = budgetCalculator.determineBudgetStatus(remainingBudgetPercent);

        return new BudgetResult(totalBudget, currentSliValue, consumedBudget,
                remainingBudget, remainingBudgetPercent, burnRate, budgetStatus);
    }

    private ErrorBudgetResponse buildResponse(SloDefinition slo, BudgetResult result,
                                               Instant windowStart, Instant now, Duration windowDuration) {
        Instant estimatedExhaustionTime = estimateExhaustionTime(result, now, windowDuration);

        return ErrorBudgetResponse.builder()
                .sloId(slo.getSloId())
                .sloName(slo.getSloName())
                .serviceName(slo.getServiceName())
                .totalBudget(result.totalBudget())
                .consumedBudget(result.consumedBudget())
                .remainingBudget(result.remainingBudget())
                .remainingBudgetPercent(result.remainingBudgetPercent())
                .burnRate(result.burnRate())
                .sloTarget(slo.getTargetValue())
                .currentSliValue(result.currentSliValue())
                .windowStart(windowStart)
                .windowEnd(now)
                .timeWindow(windowDuration)
                .budgetStatus(result.budgetStatus())
                .estimatedExhaustionTime(estimatedExhaustionTime)
                .burnRateTrend(calculateBurnRateTrend(slo.getSloId(), windowStart, now))
                .metadata(Map.of("sliType", slo.getSliType(), "environment", slo.getEnvironment()))
                .build();
    }

    private ErrorBudgetResponse buildWindowResponse(SloDefinition slo, BudgetResult result,
                                                     Instant windowStart, Instant windowEnd,
                                                     long windowSizeSeconds) {
        return ErrorBudgetResponse.builder()
                .sloId(slo.getSloId())
                .sloName(slo.getSloName())
                .serviceName(slo.getServiceName())
                .totalBudget(result.totalBudget())
                .consumedBudget(result.consumedBudget())
                .remainingBudget(result.remainingBudget())
                .remainingBudgetPercent(result.remainingBudgetPercent())
                .burnRate(result.burnRate())
                .sloTarget(slo.getTargetValue())
                .currentSliValue(result.currentSliValue())
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .timeWindow(Duration.ofSeconds(windowSizeSeconds))
                .budgetStatus(result.budgetStatus())
                .build();
    }

    private ErrorBudgetResponse mapRecordToResponse(ErrorBudgetRecord record) {
        return ErrorBudgetResponse.builder()
                .sloId(record.getSloId())
                .totalBudget(record.getTotalBudget())
                .consumedBudget(record.getConsumedBudget())
                .remainingBudget(record.getRemainingBudget())
                .remainingBudgetPercent((record.getRemainingBudget() / record.getTotalBudget()) * 100)
                .burnRate(record.getBurnRate())
                .currentSliValue(record.getCurrentSliValue())
                .windowStart(record.getWindowStart())
                .windowEnd(record.getWindowEnd())
                .budgetStatus(record.getBudgetStatus())
                .estimatedExhaustionTime(record.getEstimatedExhaustionTime())
                .build();
    }

    private Instant estimateExhaustionTime(BudgetResult result, Instant now, Duration windowDuration) {
        if (result.burnRate() > 0 && result.remainingBudget() > 0) {
            long secondsToExhaustion = (long) (result.remainingBudget() /
                    (result.burnRate() * result.totalBudget() / windowDuration.getSeconds()));
            return now.plusSeconds(secondsToExhaustion);
        }
        return null;
    }

    private void handleBudgetExhaustion(String sloId, BudgetResult result) {
        if (result.remainingBudget() <= 0) {
            budgetExhaustedCounter.increment();
            log.warn("错误预算已耗尽: sloId={}", sloId);
        }
    }

    private List<ErrorBudgetResponse.BurnRateTrend> calculateBurnRateTrend(String sloId, Instant start, Instant end) {
        List<ErrorBudgetResponse.BurnRateTrend> trend = new ArrayList<>();
        long stepHours = 24;
        Instant current = start;

        while (current.isBefore(end)) {
            Instant next = current.plusSeconds(stepHours * 3600);
            if (next.isAfter(end)) {
                next = end;
            }

            List<SliMetric> metrics = sliMetricMapper.findBySloIdAndTimeRange(sloId, current, next);
            double avgSli = budgetCalculator.calculateAverageSli(metrics);
            double hourlyBurn = Math.max(0, 0.999 - avgSli) * 24;

            trend.add(ErrorBudgetResponse.BurnRateTrend.builder()
                    .timestamp(current)
                    .burnRate(hourlyBurn)
                    .remainingBudget(1.0 - 0.999 - hourlyBurn)
                    .build());

            current = next;
        }

        return trend;
    }

    private void saveBudgetRecord(SloDefinition slo, ErrorBudgetResponse response) {
        try {
            ErrorBudgetRecord record = ErrorBudgetRecord.builder()
                    .recordId(UUID.randomUUID().toString())
                    .sloId(slo.getSloId())
                    .windowStart(response.getWindowStart())
                    .windowEnd(response.getWindowEnd())
                    .totalBudget(response.getTotalBudget())
                    .consumedBudget(response.getConsumedBudget())
                    .remainingBudget(response.getRemainingBudget())
                    .burnRate(response.getBurnRate())
                    .currentSliValue(response.getCurrentSliValue())
                    .budgetStatus(response.getBudgetStatus())
                    .estimatedExhaustionTime(response.getEstimatedExhaustionTime())
                    .metadata(objectMapper.writeValueAsString(response.getMetadata()))
                    .createdAt(Instant.now())
                    .deleted(0)
                    .build();

            errorBudgetRecordMapper.insert(record);
        } catch (Exception e) {
            log.error("保存错误预算记录失败: sloId={}", slo.getSloId(), e);
        }
    }

    private void updateBudgetGauge(SloDefinition slo, double remainingBudgetPercent) {
        String gaugeName = "error.budget.remaining.percent";
        remainingBudgetGauges.compute(slo.getSloId(), (id, existingGauge) -> {
            if (existingGauge != null) {
                meterRegistry.remove(existingGauge);
            }
            return Gauge.builder(gaugeName, () -> remainingBudgetPercent)
                    .description("剩余错误预算百分比")
                    .tag("sloId", id)
                    .tag("serviceName", slo.getServiceName())
                    .tag("environment", slo.getEnvironment())
                    .register(meterRegistry);
        });
    }

    private SloDefinition loadEnabledSlo(String sloId) {
        SloDefinition slo = sloMapper.findById(sloId)
                .orElseThrow(() -> new IllegalArgumentException("SLO不存在: " + sloId));
        if (!slo.isEnabled()) {
            throw new IllegalStateException("SLO已禁用: " + sloId);
        }
        return slo;
    }

    private Duration resolveWindowDuration(SloDefinition slo) {
        return slo.getTimeWindow() != null ? slo.getTimeWindow() : Duration.ofDays(30);
    }

    private boolean isWithinRange(Instant currentStart, long windowSizeSeconds, Instant end) {
        Instant windowEnd = currentStart.plusSeconds(windowSizeSeconds);
        return windowEnd.isBefore(end) || windowEnd.equals(end);
    }

    private record BudgetResult(double totalBudget, double currentSliValue, double consumedBudget,
                                 double remainingBudget, double remainingBudgetPercent,
                                 double burnRate, String budgetStatus) {
    }
}
