package com.iotplatform.datastream.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iotplatform.common.exception.BusinessException;
import com.iotplatform.datastream.dto.AggregationQueryDTO;
import com.iotplatform.datastream.dto.DataPointDTO;
import com.iotplatform.datastream.entity.DataAggregation;
import com.iotplatform.datastream.mapper.DataAggregationMapper;
import com.iotplatform.datastream.service.DataAggregationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataAggregationServiceImpl implements DataAggregationService {

    private final DataAggregationMapper aggregationMapper;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${iot.edge.aggregation.window-size:5000}")
    private int defaultWindowSize;

    @Value("${iot.edge.aggregation.max-windows:100}")
    private int maxWindows;

    private final Map<String, List<DataPointDTO>> windowBuffers = new ConcurrentHashMap<>();
    private final Sinks.Many<DataAggregation> aggregationSink = Sinks.many().multicast().onBackpressureBuffer();
    private final Cache<String, List<DataAggregation>> aggregationCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    private static final String[] AGGREGATION_TYPES = {
            DataAggregation.AggregationType.SUM,
            DataAggregation.AggregationType.AVG,
            DataAggregation.AggregationType.COUNT,
            DataAggregation.AggregationType.MIN,
            DataAggregation.AggregationType.MAX
    };

    @Override
    public Mono<Void> ingestDataPoint(DataPointDTO dataPoint) {
        Timer.Sample sample = Timer.start(meterRegistry);
        return Mono.fromRunnable(() -> {
            try {
                String windowKey = dataPoint.getDeviceId() + ":" + dataPoint.getStreamId() + ":" + dataPoint.getMetricName();
                windowBuffers.computeIfAbsent(windowKey, k -> new ArrayList<>()).add(dataPoint);

                redisTemplate.opsForList().rightPush(
                        "datastream:raw:" + windowKey,
                        JSONUtil.toJsonStr(dataPoint)
                ).subscribe();

                meterRegistry.counter("datastream.points.ingested",
                        "device", dataPoint.getDeviceId(),
                        "stream", dataPoint.getStreamId()).increment();

                log.debug("Data point ingested: {} - {} = {}", windowKey, dataPoint.getMetricName(), dataPoint.getMetricValue());
            } catch (Exception e) {
                log.error("Failed to ingest data point: {}", e.getMessage(), e);
                meterRegistry.counter("datastream.points.ingest.failed").increment();
                throw new BusinessException("数据点接入失败: " + e.getMessage());
            } finally {
                sample.stop(meterRegistry.timer("datastream.ingest.latency"));
            }
        });
    }

    @Override
    public Flux<DataAggregation> aggregateDataPoints(List<DataPointDTO> dataPoints, int windowSizeMs) {
        if (dataPoints.isEmpty()) {
            return Flux.empty();
        }

        Map<String, List<DataPointDTO>> grouped = new HashMap<>();
        for (DataPointDTO point : dataPoints) {
            String key = point.getDeviceId() + ":" + point.getStreamId() + ":" + point.getMetricName();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(point);
        }

        return Flux.fromIterable(grouped.entrySet())
                .flatMap(entry -> {
                    String[] parts = entry.getKey().split(":");
                    String deviceId = parts[0];
                    String streamId = parts[1];
                    String metricName = parts[2];
                    List<DataPointDTO> points = entry.getValue();

                    return Flux.fromArray(AGGREGATION_TYPES)
                            .map(type -> createAggregation(deviceId, streamId, metricName, type, points));
                });
    }

    private DataAggregation createAggregation(String deviceId, String streamId, String metricName,
                                              String aggregationType, List<DataPointDTO> points) {
        BigDecimal value = calculateAggregation(aggregationType, points);

        DataAggregation aggregation = new DataAggregation();
        aggregation.setAggregationId("agg_" + IdUtil.getSnowflakeNextIdStr());
        aggregation.setDeviceId(deviceId);
        aggregation.setStreamId(streamId);
        aggregation.setMetricName(metricName);
        aggregation.setAggregationType(aggregationType);
        aggregation.setMetricValue(value);
        aggregation.setRecordCount(points.size());
        aggregation.setWindowStart(LocalDateTime.now().minusSeconds(defaultWindowSize / 1000));
        aggregation.setWindowEnd(LocalDateTime.now());
        aggregation.setUploaded(false);
        aggregation.setCreatedAt(LocalDateTime.now());

        return aggregation;
    }

    private BigDecimal calculateAggregation(String type, List<DataPointDTO> points) {
        if (points.isEmpty()) {
            return BigDecimal.ZERO;
        }

        return switch (type) {
            case DataAggregation.AggregationType.SUM ->
                    points.stream()
                            .map(DataPointDTO::getMetricValue)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            case DataAggregation.AggregationType.AVG -> {
                BigDecimal sum = points.stream()
                        .map(DataPointDTO::getMetricValue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                yield sum.divide(BigDecimal.valueOf(points.size()), 6, RoundingMode.HALF_UP);
            }
            case DataAggregation.AggregationType.COUNT -> BigDecimal.valueOf(points.size());
            case DataAggregation.AggregationType.MIN ->
                    points.stream()
                            .map(DataPointDTO::getMetricValue)
                            .min(BigDecimal::compareTo)
                            .orElse(BigDecimal.ZERO);
            case DataAggregation.AggregationType.MAX ->
                    points.stream()
                            .map(DataPointDTO::getMetricValue)
                            .max(BigDecimal::compareTo)
                            .orElse(BigDecimal.ZERO);
            default -> BigDecimal.ZERO;
        };
    }

    @Override
    public Mono<List<DataAggregation>> getAggregations(AggregationQueryDTO query) {
        return Mono.fromCallable(() -> {
            List<DataAggregation> results = new ArrayList<>();
            if (query.getAggregationTypes() != null && !query.getAggregationTypes().isEmpty()) {
                for (String type : query.getAggregationTypes()) {
                    results.addAll(aggregationMapper.findByWindow(
                            query.getDeviceId(),
                            query.getStreamId(),
                            query.getMetricName(),
                            type,
                            query.getStartTime(),
                            query.getEndTime()
                    ));
                }
            }
            return results;
        });
    }

    @Override
    public Mono<IPage<DataAggregation>> getAggregationPage(AggregationQueryDTO query,
                                                           Integer pageNum, Integer pageSize) {
        return Mono.fromCallable(() -> {
            Page<DataAggregation> page = new Page<>(pageNum, pageSize);
            String type = query.getAggregationTypes() != null && !query.getAggregationTypes().isEmpty()
                    ? query.getAggregationTypes().get(0) : null;
            return aggregationMapper.selectAggregationPage(page,
                    query.getDeviceId(),
                    query.getStreamId(),
                    query.getMetricName(),
                    type,
                    query.getStartTime(),
                    query.getEndTime());
        });
    }

    @Override
    public Mono<List<DataAggregation>> getUnuploadedAggregations(int limit) {
        return Mono.fromCallable(() -> aggregationMapper.findUnuploaded(limit));
    }

    @Override
    @Transactional
    public Mono<Void> markAsUploaded(Long aggregationId) {
        return Mono.fromCallable(() -> {
            int updated = aggregationMapper.markAsUploaded(aggregationId, LocalDateTime.now());
            if (updated == 0) {
                throw new BusinessException(404, "聚合记录不存在: " + aggregationId);
            }
            log.debug("Aggregation marked as uploaded: {}", aggregationId);
            return null;
        });
    }

    @Override
    @Transactional
    public Mono<Void> markBatchAsUploaded(List<Long> aggregationIds) {
        return Flux.fromIterable(aggregationIds)
                .flatMap(this::markAsUploaded)
                .then();
    }

    @Override
    public Flux<DataAggregation> streamAggregations(String deviceId, String streamId) {
        return aggregationSink.asFlux()
                .filter(agg -> agg.getDeviceId().equals(deviceId) && agg.getStreamId().equals(streamId));
    }

    @Override
    public Mono<List<DataAggregation>> getDeviceAggregations(String deviceId, String streamId) {
        String cacheKey = deviceId + ":" + streamId;
        List<DataAggregation> cached = aggregationCache.getIfPresent(cacheKey);
        if (cached != null) {
            return Mono.just(cached);
        }

        return Mono.fromCallable(() -> {
            List<DataAggregation> results = aggregationMapper.findByDeviceAndStream(deviceId, streamId);
            aggregationCache.put(cacheKey, results);
            return results;
        });
    }

    @Scheduled(fixedRateString = "${iot.edge.aggregation.window-size:5000}")
    @Transactional
    public void processWindows() {
        if (windowBuffers.isEmpty()) {
            return;
        }

        log.debug("Processing aggregation windows, buffer count: {}", windowBuffers.size());
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            List<DataPointDTO> allPoints = new ArrayList<>();
            for (List<DataPointDTO> buffer : windowBuffers.values()) {
                allPoints.addAll(buffer);
                buffer.clear();
            }

            if (allPoints.isEmpty()) {
                return;
            }

            aggregateDataPoints(allPoints, defaultWindowSize)
                    .collectList()
                    .doOnNext(aggregations -> {
                        for (DataAggregation agg : aggregations) {
                            aggregationMapper.insert(agg);
                            aggregationSink.tryEmitNext(agg);
                        }
                        log.info("Aggregated {} data points into {} aggregations", allPoints.size(), aggregations.size());
                        meterRegistry.counter("datastream.aggregations.created").increment(aggregations.size());
                    })
                    .subscribe();

        } catch (Exception e) {
            log.error("Failed to process aggregation windows: {}", e.getMessage(), e);
            meterRegistry.counter("datastream.aggregation.failed").increment();
        } finally {
            sample.stop(meterRegistry.timer("datastream.aggregation.latency"));
        }
    }
}
