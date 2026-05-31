package com.tsdbproxy.timeseries.service;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.tsdbproxy.common.entity.TimeSeriesData;
import com.tsdbproxy.common.mapper.TimeSeriesDataMapper;
import com.tsdbproxy.timeseries.compression.GorillaCompressor;
import com.tsdbproxy.timeseries.downsample.Downsampler;
import com.tsdbproxy.timeseries.dto.TimeSeriesPoint;
import com.tsdbproxy.timeseries.dto.TimeSeriesQueryRequest;
import com.tsdbproxy.timeseries.dto.TimeSeriesWriteRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TimeSeriesService {

    private final TimeSeriesDataMapper timeSeriesDataMapper;
    private final GorillaCompressor gorillaCompressor;
    private final Downsampler downsampler;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final Cache<String, Object> caffeineCache;

    public Mono<Void> write(TimeSeriesWriteRequest request) {
        return Mono.fromRunnable(() -> {
            TimeSeriesData data = new TimeSeriesData();
            data.setMetric(request.getMetric());
            data.setTags(JSONUtil.toJsonStr(request.getTags()));
            data.setTimestamp(request.getTimestamp());
            data.setValue(request.getValue());
            data.setResolution("raw");
            data.setCompressionType(request.getCompressionType());

            timeSeriesDataMapper.insert(data);

            String cacheKey = "ts:" + request.getMetric() + ":" + request.getTimestamp().toEpochSecond(ZoneOffset.UTC);
            caffeineCache.put(cacheKey, request.getValue());
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Flux<TimeSeriesPoint> query(TimeSeriesQueryRequest request) {
        return Flux.defer(() -> {
            log.info("查询时序数据: metric={}, startTime={}, endTime={}, resolution={}",
                    request.getMetric(), request.getStartTime(), request.getEndTime(), request.getResolution());

            List<TimeSeriesData> rawData = timeSeriesDataMapper.selectList(null);

            List<TimeSeriesPoint> points = rawData.stream()
                    .filter(d -> request.getMetric().equals(d.getMetric()))
                    .filter(d -> !d.getTimestamp().isBefore(request.getStartTime()))
                    .filter(d -> !d.getTimestamp().isAfter(request.getEndTime()))
                    .sorted(Comparator.comparing(TimeSeriesData::getTimestamp))
                    .map(d -> new TimeSeriesPoint(d.getTimestamp(), d.getValue()))
                    .toList();

            if (!"raw".equals(request.getResolution())) {
                points = downsampler.downsample(new ArrayList<>(points), request.getResolution(), request.getDownsampleFunction());
            }

            return Flux.fromIterable(points);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<byte[]> compressData(List<TimeSeriesPoint> data) {
        return Mono.fromCallable(() -> {
            List<GorillaCompressor.TimeValuePair> pairs = data.stream()
                    .map(p -> new GorillaCompressor.TimeValuePair(
                            p.getTimestamp().toEpochSecond(ZoneOffset.UTC),
                            p.getValue()))
                    .toList();

            return gorillaCompressor.compress(pairs);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<TimeSeriesPoint>> decompressData(byte[] compressed) {
        return Mono.fromCallable(() -> {
            List<GorillaCompressor.TimeValuePair> pairs = gorillaCompressor.decompress(compressed);
            return pairs.stream()
                    .map(p -> new TimeSeriesPoint(
                            LocalDateTime.ofEpochSecond(p.getTimestamp(), 0, ZoneOffset.UTC),
                            p.getValue()))
                    .toList();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
