package com.datastandard.modules.anomaly;

import com.datastandard.common.model.AnomalyDetectionResult;
import com.datastandard.modules.anomaly.dto.AlgorithmConfig;
import com.datastandard.modules.anomaly.dto.BaselineQuery;
import com.datastandard.modules.anomaly.mapper.AnomalyDetectionResultMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BaselineManager {

    private final Cache<String, BaselineData> baselineCache;
    private final AnomalyDetectionResultMapper anomalyResultMapper;

    private static final String CACHE_KEY_PREFIX = "baseline:";
    private static final int CACHE_MAX_SIZE = 1000;
    private static final int CACHE_EXPIRE_HOURS = 6;

    public BaselineManager(AnomalyDetectionResultMapper anomalyResultMapper) {
        this.anomalyResultMapper = anomalyResultMapper;
        this.baselineCache = Caffeine.newBuilder()
                .maximumSize(CACHE_MAX_SIZE)
                .expireAfterWrite(CACHE_EXPIRE_HOURS, TimeUnit.HOURS)
                .build();
    }

    public Mono<BaselineData> getBaseline(BaselineQuery query) {
        String cacheKey = buildCacheKey(query);
        return Mono.fromCallable(() -> {
            BaselineData cached = baselineCache.getIfPresent(cacheKey);
            if (cached != null && !isExpired(cached, query)) {
                log.debug("使用缓存基线数据: {}", cacheKey);
                return cached;
            }

            BaselineData baseline = calculateBaseline(query);
            baselineCache.put(cacheKey, baseline);
            log.info("基线数据计算完成: metricCode={}, 数据点={}", query.getMetricCode(),
                    baseline.getHistoricalValues() != null ? baseline.getHistoricalValues().size() : 0);
            return baseline;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<BaselineData> updateBaseline(String metricCode, Long entityId, Long instanceId,
                                             BigDecimal newValue, List<String> dimensions) {
        return Mono.fromCallable(() -> {
            BaselineQuery query = BaselineQuery.builder()
                    .metricCode(metricCode)
                    .entityId(entityId)
                    .instanceId(instanceId)
                    .dimensions(dimensions)
                    .periodDays(30)
                    .includeSeasonal(true)
                    .build();

            BaselineData existing = baselineCache.getIfPresent(buildCacheKey(query));
            if (existing == null) {
                existing = calculateBaseline(query);
            }

            existing.addHistoricalValue(newValue);
            existing.setLastUpdated(LocalDateTime.now());
            existing.recalculateStatistics();

            baselineCache.put(buildCacheKey(query), existing);
            log.debug("基线数据已更新: metricCode={}, newValue={}", metricCode, newValue);
            return existing;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<BaselineData> batchGetBaselines(List<BaselineQuery> queries) {
        return Flux.fromIterable(queries)
                .flatMap(this::getBaseline)
                .onErrorContinue((e, q) -> log.error("批量获取基线失败: {}", q, e));
    }

    public Mono<Void> invalidateCache(BaselineQuery query) {
        return Mono.fromRunnable(() -> {
            String cacheKey = buildCacheKey(query);
            baselineCache.invalidate(cacheKey);
            log.info("基线缓存已失效: {}", cacheKey);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Void> invalidateAllCache() {
        return Mono.fromRunnable(() -> {
            baselineCache.invalidateAll();
            log.info("所有基线缓存已失效");
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<BigDecimal> calculateExpectedValue(BaselineQuery query, LocalDateTime timestamp) {
        return getBaseline(query)
                .map(baseline -> {
                    BigDecimal expected = baseline.getMean();
                    if (Boolean.TRUE.equals(query.getIncludeSeasonal()) && baseline.getSeasonalComponent() != null) {
                        int seasonIndex = calculateSeasonIndex(timestamp, query, baseline);
                        BigDecimal seasonal = baseline.getSeasonalComponent().get(seasonIndex);
                        expected = expected.add(seasonal);
                    }
                    return expected;
                });
    }

    public Mono<Map<String, BigDecimal>> getThresholds(BaselineQuery query, AlgorithmConfig config) {
        return getBaseline(query)
                .map(baseline -> {
                    BigDecimal stdDev = baseline.getStdDev();
                    BigDecimal mean = baseline.getMean();
                    BigDecimal sensitivity = config.getSensitivity() != null ?
                            config.getSensitivity() : new BigDecimal("3.0");

                    BigDecimal upperWarning = mean.add(stdDev.multiply(sensitivity));
                    BigDecimal lowerWarning = mean.subtract(stdDev.multiply(sensitivity));
                    BigDecimal upperCritical = mean.add(stdDev.multiply(sensitivity.multiply(new BigDecimal("1.5"))));
                    BigDecimal lowerCritical = mean.subtract(stdDev.multiply(sensitivity.multiply(new BigDecimal("1.5"))));

                    return Map.of(
                            "mean", mean,
                            "stdDev", stdDev,
                            "upperWarning", upperWarning,
                            "lowerWarning", lowerWarning,
                            "upperCritical", upperCritical,
                            "lowerCritical", lowerCritical
                    );
                });
    }

    private BaselineData calculateBaseline(BaselineQuery query) {
        List<BigDecimal> historicalValues = fetchHistoricalData(query);

        if (historicalValues.isEmpty()) {
            log.warn("无历史数据用于基线计算: metricCode={}", query.getMetricCode());
            return new BaselineData(Collections.emptyList(), query);
        }

        BaselineData baseline = new BaselineData(historicalValues, query);

        if (Boolean.TRUE.equals(query.getIncludeSeasonal())) {
            baseline.setSeasonalComponent(extractSeasonal(historicalValues, query));
        }

        return baseline;
    }

    private List<BigDecimal> fetchHistoricalData(BaselineQuery query) {
        LocalDateTime endTime = query.getEndTime() != null ? query.getEndTime() : LocalDateTime.now();
        LocalDateTime startTime = query.getStartTime() != null ? query.getStartTime() :
                endTime.minusDays(query.getPeriodDays() != null ? query.getPeriodDays() : 30);

        List<AnomalyDetectionResult> results = anomalyResultMapper
                .findByMetricAndTimeRange(query.getMetricCode(), startTime, endTime);

        return results.stream()
                .map(AnomalyDetectionResult::getAnomalyScore)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<BigDecimal> extractSeasonal(List<BigDecimal> values, BaselineQuery query) {
        int period = 7;
        if ("weekly".equalsIgnoreCase(query.getSeasonalityType())) {
            period = 7;
        } else if ("monthly".equalsIgnoreCase(query.getSeasonalityType())) {
            period = 30;
        } else if ("daily".equalsIgnoreCase(query.getSeasonalityType())) {
            period = 24;
        }

        if (values.size() < period * 2) {
            return Collections.nCopies(period, BigDecimal.ZERO);
        }

        List<BigDecimal> seasonal = new ArrayList<>(period);
        for (int i = 0; i < period; i++) {
            List<BigDecimal> group = new ArrayList<>();
            for (int j = i; j < values.size(); j += period) {
                group.add(values.get(j));
            }
            seasonal.add(calculateMedian(group));
        }

        BigDecimal seasonalMean = calculateMean(seasonal);
        return seasonal.stream()
                .map(v -> v.subtract(seasonalMean))
                .collect(Collectors.toList());
    }

    private String buildCacheKey(BaselineQuery query) {
        return CACHE_KEY_PREFIX + query.getMetricCode() + ":" +
                (query.getEntityId() != null ? query.getEntityId() : "null") + ":" +
                (query.getInstanceId() != null ? query.getInstanceId() : "null") + ":" +
                (query.getDimensions() != null ? String.join(",", query.getDimensions()) : "");
    }

    private boolean isExpired(BaselineData baseline, BaselineQuery query) {
        if (baseline.getLastUpdated() == null) return true;
        int maxAgeHours = query.getPeriodDays() != null ?
                Math.max(1, query.getPeriodDays() / 4) : 6;
        return baseline.getLastUpdated().isBefore(LocalDateTime.now().minusHours(maxAgeHours));
    }

    private int calculateSeasonIndex(LocalDateTime timestamp, BaselineQuery query, BaselineData baseline) {
        if (baseline.getSeasonalComponent() == null || baseline.getSeasonalComponent().isEmpty()) {
            return 0;
        }
        int period = baseline.getSeasonalComponent().size();
        int dayOfWeek = timestamp.getDayOfWeek().getValue() - 1;
        if ("monthly".equalsIgnoreCase(query.getSeasonalityType())) {
            dayOfWeek = timestamp.getDayOfMonth() - 1;
        } else if ("daily".equalsIgnoreCase(query.getSeasonalityType())) {
            dayOfWeek = timestamp.getHour();
        }
        return dayOfWeek % period;
    }

    private BigDecimal calculateMean(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMedian(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int size = sorted.size();
        if (size % 2 == 0) {
            return sorted.get(size / 2 - 1).add(sorted.get(size / 2))
                    .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
        } else {
            return sorted.get(size / 2);
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BaselineData {
        private List<BigDecimal> historicalValues;
        private BigDecimal mean;
        private BigDecimal median;
        private BigDecimal stdDev;
        private BigDecimal min;
        private BigDecimal max;
        private BigDecimal percentile95;
        private BigDecimal percentile99;
        private List<BigDecimal> seasonalComponent;
        private LocalDateTime lastUpdated;
        private BaselineQuery query;

        public BaselineData(List<BigDecimal> historicalValues, BaselineQuery query) {
            this.historicalValues = new ArrayList<>(historicalValues);
            this.query = query;
            this.lastUpdated = LocalDateTime.now();
            recalculateStatistics();
        }

        public void addHistoricalValue(BigDecimal value) {
            if (this.historicalValues == null) {
                this.historicalValues = new ArrayList<>();
            }
            this.historicalValues.add(value);
            if (this.historicalValues.size() > 10000) {
                this.historicalValues = this.historicalValues.subList(
                        this.historicalValues.size() - 10000, this.historicalValues.size());
            }
        }

        public void recalculateStatistics() {
            if (historicalValues == null || historicalValues.isEmpty()) {
                this.mean = BigDecimal.ZERO;
                this.median = BigDecimal.ZERO;
                this.stdDev = BigDecimal.ZERO;
                this.min = BigDecimal.ZERO;
                this.max = BigDecimal.ZERO;
                this.percentile95 = BigDecimal.ZERO;
                this.percentile99 = BigDecimal.ZERO;
                return;
            }

            List<BigDecimal> sorted = historicalValues.stream().sorted().toList();
            int n = sorted.size();

            this.mean = calculateMeanInternal(sorted);
            this.median = sorted.get(n / 2);
            this.stdDev = calculateStdDevInternal(sorted, this.mean);
            this.min = sorted.get(0);
            this.max = sorted.get(n - 1);
            this.percentile95 = calculatePercentile(sorted, 95);
            this.percentile99 = calculatePercentile(sorted, 99);
        }

        private BigDecimal calculateMeanInternal(List<BigDecimal> values) {
            BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
        }

        private BigDecimal calculateStdDevInternal(List<BigDecimal> values, BigDecimal mean) {
            BigDecimal sumSquaredDiff = values.stream()
                    .map(v -> v.subtract(mean).pow(2))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal variance = sumSquaredDiff.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
            return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
        }

        private BigDecimal calculatePercentile(List<BigDecimal> sorted, int percentile) {
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));
            return sorted.get(index);
        }
    }
}
