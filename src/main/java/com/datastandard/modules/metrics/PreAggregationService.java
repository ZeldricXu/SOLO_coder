package com.datastandard.modules.metrics;

import com.datastandard.modules.metrics.dto.AggregateQuery;
import com.datastandard.modules.metrics.dto.DimensionFilter;
import com.datastandard.modules.metrics.dto.MetricResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PreAggregationService {

    private final RedisStorageAdapter redisStorageAdapter;
    private final MySqlStorageAdapter mySqlStorageAdapter;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedRate = 60000)
    public void runMinuteAggregation() {
        log.debug("Starting minute-level aggregation");
        Instant endTime = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant startTime = endTime.minus(1, ChronoUnit.MINUTES);
        aggregateForLevel(startTime, endTime, AggregateQuery.AggregateLevel.MINUTE)
                .subscribe(
                        count -> log.info("Minute aggregation completed, {} data points processed", count),
                        error -> log.error("Minute aggregation failed", error)
                );
    }

    @Scheduled(cron = "0 0 * * * *")
    public void runHourAggregation() {
        log.debug("Starting hour-level aggregation");
        Instant endTime = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant startTime = endTime.minus(1, ChronoUnit.HOURS);
        aggregateForLevel(startTime, endTime, AggregateQuery.AggregateLevel.HOUR)
                .subscribe(
                        count -> log.info("Hour aggregation completed, {} data points processed", count),
                        error -> log.error("Hour aggregation failed", error)
                );
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void runDayAggregation() {
        log.debug("Starting day-level aggregation");
        Instant endTime = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant startTime = endTime.minus(1, ChronoUnit.DAYS);
        aggregateForLevel(startTime, endTime, AggregateQuery.AggregateLevel.DAY)
                .subscribe(
                        count -> log.info("Day aggregation completed, {} data points processed", count),
                        error -> log.error("Day aggregation failed", error)
                );
    }

    public Mono<Long> aggregateForLevel(Instant startTime, Instant endTime, AggregateQuery.AggregateLevel level) {
        AggregateQuery sourceQuery = AggregateQuery.builder()
                .metricName("*")
                .startTime(startTime)
                .endTime(endTime)
                .aggregateLevel(AggregateQuery.AggregateLevel.RAW)
                .build();

        return getAllMetricNames()
                .flatMapMany(Flux::fromIterable)
                .flatMap(metricName -> {
                    AggregateQuery query = AggregateQuery.builder()
                            .metricName(metricName)
                            .startTime(startTime)
                            .endTime(endTime)
                            .aggregateLevel(AggregateQuery.AggregateLevel.RAW)
                            .build();
                    return queryWithFilter(query)
                            .collectList()
                            .flatMap(metrics -> aggregateAndStore(metricName, metrics, startTime, endTime, level));
                })
                .count();
    }

    private Mono<Long> aggregateAndStore(String metricName, List<MetricResponse> metrics,
                                         Instant startTime, Instant endTime, AggregateQuery.AggregateLevel level) {
        if (metrics.isEmpty()) {
            return Mono.just(0L);
        }

        Map<List<String>, List<MetricResponse>> grouped = metrics.stream()
                .collect(Collectors.groupingBy(m -> {
                    List<String> dimKeys = new ArrayList<>(m.getDimensions().keySet());
                    dimKeys.sort(String::compareTo);
                    List<String> key = new ArrayList<>();
                    for (String k : dimKeys) {
                        key.add(k + "=" + m.getDimensions().get(k));
                    }
                    return key;
                }));

        List<Mono<Boolean>> saveOperations = new ArrayList<>();
        for (Map.Entry<List<String>, List<MetricResponse>> entry : grouped.entrySet()) {
            Map<String, String> dimensions = parseDimensionKey(entry.getKey());
            Map<String, Object> aggregated = calculateAggregates(entry.getValue());

            Map<String, Object> metricData = new HashMap<>();
            metricData.put("metricName", metricName);
            metricData.putAll(aggregated);

            try {
                String metricsJson = objectMapper.writeValueAsString(metricData);
                String dimensionsJson = objectMapper.writeValueAsString(dimensions);

                com.datastandard.modules.metrics.entity.MetricSnapshot snapshot =
                        com.datastandard.modules.metrics.entity.MetricSnapshot.builder()
                                .timestamp(startTime)
                                .metrics(metricsJson)
                                .dimensions(dimensionsJson)
                                .aggregateLevel(level.name().toLowerCase())
                                .createdAt(Instant.now())
                                .build();

                saveOperations.add(mySqlStorageAdapter.batchWrite(List.of(Map.of(
                        "metricName", metricName,
                        "value", aggregated.get("avg"),
                        "dimensions", dimensions,
                        "timestamp", startTime
                ))));
            } catch (Exception e) {
                log.error("Failed to serialize aggregated data", e);
            }
        }

        return Flux.merge(saveOperations)
                .count()
                .doOnNext(count -> log.info("Stored {} aggregated {} records for metric: {}",
                        count, level, metricName));
    }

    private Map<String, Object> calculateAggregates(List<MetricResponse> metrics) {
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        long count = metrics.size();

        List<Double> values = new ArrayList<>();
        for (MetricResponse m : metrics) {
            double v = m.getValue();
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
            values.add(v);
        }

        values.sort(Double::compareTo);
        double p95 = calculatePercentile(values, 95);
        double p99 = calculatePercentile(values, 99);

        Map<String, Object> result = new HashMap<>();
        result.put("sum", sum);
        result.put("avg", count > 0 ? sum / count : 0);
        result.put("min", min == Double.MAX_VALUE ? 0 : min);
        result.put("max", max == Double.MIN_VALUE ? 0 : max);
        result.put("count", count);
        result.put("p95", p95);
        result.put("p99", p99);

        return result;
    }

    private double calculatePercentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        double index = (percentile / 100.0) * (sortedValues.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sortedValues.get(lower);
        }
        double weight = index - lower;
        return sortedValues.get(lower) * (1 - weight) + sortedValues.get(upper) * weight;
    }

    private Map<String, String> parseDimensionKey(List<String> key) {
        Map<String, String> dimensions = new HashMap<>();
        for (String part : key) {
            String[] parts = part.split("=", 2);
            if (parts.length == 2) {
                dimensions.put(parts[0], parts[1]);
            }
        }
        return dimensions;
    }

    private Mono<List<String>> getAllMetricNames() {
        return redisStorageAdapter.getAllMetricNames()
                .map(ArrayList::new);
    }

    @Cacheable(value = "metricQueries", key = "#query.hashCode()")
    public Flux<MetricResponse> queryWithFilter(AggregateQuery query) {
        Flux<MetricResponse> hotData = Flux.empty();
        Flux<MetricResponse> coldData = Flux.empty();

        Instant hotCutoff = Instant.now().minus(Duration.ofHours(24));
        if (query.getEndTime().isAfter(hotCutoff)) {
            Instant hotStart = query.getStartTime().isAfter(hotCutoff) ? query.getStartTime() : hotCutoff;
            AggregateQuery hotQuery = AggregateQuery.builder()
                    .metricName(query.getMetricName())
                    .startTime(hotStart)
                    .endTime(query.getEndTime())
                    .aggregateLevel(query.getAggregateLevel())
                    .dimensionFilters(query.getDimensionFilters())
                    .groupByDimensions(query.getGroupByDimensions())
                    .downsampling(query.getDownsampling())
                    .build();
            hotData = redisStorageAdapter.query(hotQuery);
        }

        if (query.getStartTime().isBefore(hotCutoff)) {
            Instant coldEnd = query.getEndTime().isBefore(hotCutoff) ? query.getEndTime() : hotCutoff;
            AggregateQuery coldQuery = AggregateQuery.builder()
                    .metricName(query.getMetricName())
                    .startTime(query.getStartTime())
                    .endTime(coldEnd)
                    .aggregateLevel(query.getAggregateLevel())
                    .dimensionFilters(query.getDimensionFilters())
                    .groupByDimensions(query.getGroupByDimensions())
                    .downsampling(query.getDownsampling())
                    .build();
            coldData = mySqlStorageAdapter.query(coldQuery);
        }

        return Flux.merge(coldData, hotData)
                .filter(response -> applyDimensionFilters(response, query.getDimensionFilters()))
                .sort(Comparator.comparing(MetricResponse::getTimestamp));
    }

    private boolean applyDimensionFilters(MetricResponse response, List<DimensionFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        Map<String, String> dimensions = response.getDimensions();
        for (DimensionFilter filter : filters) {
            String value = dimensions.get(filter.getKey());
            if (!matchesFilter(value, filter)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesFilter(String value, DimensionFilter filter) {
        if (value == null) {
            return false;
        }
        return switch (filter.getOperator()) {
            case EQ -> value.equals(filter.getValue());
            case NEQ -> !value.equals(filter.getValue());
            case IN -> filter.getValues() != null && filter.getValues().contains(value);
            case NOT_IN -> filter.getValues() == null || !filter.getValues().contains(value);
            case CONTAINS -> value.contains(filter.getValue());
            case GT -> Double.parseDouble(value) > Double.parseDouble(filter.getValue());
            case LT -> Double.parseDouble(value) < Double.parseDouble(filter.getValue());
            case GTE -> Double.parseDouble(value) >= Double.parseDouble(filter.getValue());
            case LTE -> Double.parseDouble(value) <= Double.parseDouble(filter.getValue());
        };
    }

    @Cacheable(value = "aggregateResults", key = "#query.hashCode() + '-' + #function")
    public Mono<Map<String, Object>> aggregate(AggregateQuery query, AggregateQuery.AggregateFunction function) {
        return queryWithFilter(query)
                .collectList()
                .map(metrics -> {
                    Map<String, Object> result = new HashMap<>();
                    if (metrics.isEmpty()) {
                        result.put("value", 0);
                        result.put("count", 0);
                        return result;
                    }

                    List<Double> values = metrics.stream()
                            .map(MetricResponse::getValue)
                            .sorted()
                            .collect(Collectors.toList());

                    double value = switch (function) {
                        case SUM -> values.stream().mapToDouble(Double::doubleValue).sum();
                        case AVG -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                        case MIN -> values.get(0);
                        case MAX -> values.get(values.size() - 1);
                        case COUNT -> (double) values.size();
                        case PERCENTILE_95 -> calculatePercentile(values, 95);
                        case PERCENTILE_99 -> calculatePercentile(values, 99);
                    };

                    result.put("value", value);
                    result.put("count", values.size());
                    result.put("function", function);
                    return result;
                });
    }

    public Flux<MetricResponse> downsample(Flux<MetricResponse> data, int intervalSeconds,
                                           AggregateQuery.AggregateFunction function) {
        return data
                .collectMultisample(
                        m -> m.getTimestamp().getEpochSecond() / intervalSeconds,
                        window -> window.collectList().map(metrics -> {
                            if (metrics.isEmpty()) {
                                return null;
                            }
                            double value = switch (function) {
                                case SUM -> metrics.stream().mapToDouble(MetricResponse::getValue).sum();
                                case AVG -> metrics.stream().mapToDouble(MetricResponse::getValue).average().orElse(0);
                                case MIN -> metrics.stream().mapToDouble(MetricResponse::getValue).min().orElse(0);
                                case MAX -> metrics.stream().mapToDouble(MetricResponse::getValue).max().orElse(0);
                                case COUNT -> (double) metrics.size();
                                default -> metrics.stream().mapToDouble(MetricResponse::getValue).average().orElse(0);
                            };
                            return MetricResponse.builder()
                                    .metricName(metrics.get(0).getMetricName())
                                    .value(value)
                                    .dimensions(metrics.get(0).getDimensions())
                                    .timestamp(metrics.get(0).getTimestamp())
                                    .build();
                        })
                )
                .flatMap(mono -> mono)
                .filter(Objects::nonNull);
    }
}
