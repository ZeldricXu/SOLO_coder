package com.device.platform.aggregation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.device.platform.common.BusinessException;
import com.device.platform.common.JsonUtils;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.AggregationResultResponse;
import com.device.platform.dto.DataPointIngestRequest;
import com.device.platform.entity.AggregationWindow;
import com.device.platform.entity.RawDataPoint;
import com.device.platform.mapper.AggregationWindowMapper;
import com.device.platform.mapper.RawDataPointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataAggregationService {

    private final RawDataPointMapper rawDataPointMapper;
    private final AggregationWindowMapper aggregationWindowMapper;

    @Value("${aggregation.window-size-ms:60000}")
    private long defaultWindowSizeMs;

    @Value("${aggregation.batch-size:1000}")
    private int aggregationBatchSize;

    @Transactional
    public Mono<List<RawDataPoint>> ingestDataPoints(DataPointIngestRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            ctx.putAttribute("deviceId", request.getDeviceId());
            ctx.putAttribute("pointCount", request.getPoints().size());

            List<RawDataPoint> savedPoints = new ArrayList<>();

            for (DataPointIngestRequest.DataPoint point : request.getPoints()) {
                RawDataPoint dataPoint = new RawDataPoint();
                dataPoint.setPointId(generatePointId());
                dataPoint.setDeviceId(request.getDeviceId());
                dataPoint.setMetricName(point.getMetricName());
                dataPoint.setMetricValue(point.getMetricValue());
                dataPoint.setUnit(point.getUnit());
                dataPoint.setCollectedAt(point.getCollectedAt());
                dataPoint.setAggregated(false);

                if (point.getTags() != null && !point.getTags().isEmpty()) {
                    dataPoint.setTags(JsonUtils.toJson(point.getTags()));
                }

                rawDataPointMapper.insert(dataPoint);
                savedPoints.add(dataPoint);
            }

            log.debug("数据点已接收: deviceId={}, count={}, traceId={}",
                    request.getDeviceId(), request.getPoints().size(), ctx.getTraceId());

            return savedPoints;
        });
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void aggregateData() {
        List<RawDataPoint> unaggregatedPoints = rawDataPointMapper.selectList(
                new LambdaQueryWrapper<RawDataPoint>()
                        .eq(RawDataPoint::isAggregated, false)
                        .orderByAsc(RawDataPoint::getCollectedAt)
                        .last("LIMIT " + aggregationBatchSize));

        if (unaggregatedPoints.isEmpty()) {
            return;
        }

        log.info("开始数据聚合: unaggregatedCount={}", unaggregatedPoints.size());

        Map<String, List<RawDataPoint>> groupedPoints = unaggregatedPoints.stream()
                .collect(Collectors.groupingBy(p -> p.getDeviceId() + "|" + p.getMetricName()));

        int windowCount = 0;
        for (Map.Entry<String, List<RawDataPoint>> entry : groupedPoints.entrySet()) {
            String[] key = entry.getKey().split("\\|");
            String deviceId = key[0];
            String metricName = key[1];
            List<RawDataPoint> points = entry.getValue();

            Map<Long, List<RawDataPoint>> windowedPoints = points.stream()
                    .collect(Collectors.groupingBy(p ->
                            (p.getCollectedAt().toEpochMilli() / defaultWindowSizeMs) * defaultWindowSizeMs));

            for (Map.Entry<Long, List<RawDataPoint>> windowEntry : windowedPoints.entrySet()) {
                long windowStart = windowEntry.getKey();
                List<RawDataPoint> windowPoints = windowEntry.getValue();
                createAggregationWindow(deviceId, metricName, windowStart, windowPoints);
                markPointsAsAggregated(windowPoints);
                windowCount++;
            }
        }

        log.info("数据聚合完成: windowsCreated={}, pointsProcessed={}",
                windowCount, unaggregatedPoints.size());
    }

    @Transactional
    protected AggregationWindow createAggregationWindow(String deviceId, String metricName,
                                                        long windowStartMs, List<RawDataPoint> points) {
        long windowEndMs = windowStartMs + defaultWindowSizeMs;

        List<Double> values = points.stream()
                .map(RawDataPoint::getMetricValue)
                .sorted()
                .collect(Collectors.toList());

        long count = values.size();
        double min = values.get(0);
        double max = values.get(count - 1);
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        double avg = sum / count;

        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - avg, 2))
                .average()
                .orElse(0.0);
        double stdDev = Math.sqrt(variance);

        Map<Double, Double> percentiles = calculatePercentiles(values);

        AggregationWindow window = new AggregationWindow();
        window.setWindowId(generateWindowId());
        window.setDeviceId(deviceId);
        window.setMetricName(metricName);
        window.setWindowStartMs(windowStartMs);
        window.setWindowEndMs(windowEndMs);
        window.setWindowSizeMs(defaultWindowSizeMs);
        window.setRecordCount(count);
        window.setMinValue(min);
        window.setMaxValue(max);
        window.setAvgValue(avg);
        window.setSumValue(sum);
        window.setVariance(variance);
        window.setStdDev(stdDev);
        window.setPercentiles(JsonUtils.toJson(percentiles));
        window.setAggregatedAt(Instant.now());
        window.setUploaded(false);

        aggregationWindowMapper.insert(window);

        return window;
    }

    private Map<Double, Double> calculatePercentiles(List<Double> sortedValues) {
        Map<Double, Double> percentiles = new LinkedHashMap<>();
        int size = sortedValues.size();

        percentiles.put(50.0, sortedValues.get((int) (0.50 * (size - 1))));
        percentiles.put(75.0, sortedValues.get((int) (0.75 * (size - 1))));
        percentiles.put(90.0, sortedValues.get((int) (0.90 * (size - 1))));
        percentiles.put(95.0, sortedValues.get((int) (0.95 * (size - 1))));
        percentiles.put(99.0, sortedValues.get((int) (0.99 * (size - 1))));

        return percentiles;
    }

    @Transactional
    protected void markPointsAsAggregated(List<RawDataPoint> points) {
        List<Long> ids = points.stream().map(RawDataPoint::getId).collect(Collectors.toList());
        rawDataPointMapper.update(null, new LambdaUpdateWrapper<RawDataPoint>()
                .in(RawDataPoint::getId, ids)
                .set(RawDataPoint::isAggregated, true));
    }

    public Flux<AggregationResultResponse> getAggregationResults(String deviceId, String metricName,
                                                                 Long startTimeMs, Long endTimeMs, TraceContext ctx) {
        return Flux.fromIterable(aggregationWindowMapper.selectList(
                new LambdaQueryWrapper<AggregationWindow>()
                        .eq(deviceId != null, AggregationWindow::getDeviceId, deviceId)
                        .eq(metricName != null, AggregationWindow::getMetricName, metricName)
                        .ge(startTimeMs != null, AggregationWindow::getWindowStartMs, startTimeMs)
                        .le(endTimeMs != null, AggregationWindow::getWindowEndMs, endTimeMs)
                        .orderByAsc(AggregationWindow::getWindowStartMs))
                .stream()
                .map(this::toAggregationResponse)
                .collect(Collectors.toList()));
    }

    @Transactional
    public Mono<Long> uploadAggregationResults(String deviceId, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            List<AggregationWindow> windows = aggregationWindowMapper.selectList(
                    new LambdaQueryWrapper<AggregationWindow>()
                            .eq(deviceId != null, AggregationWindow::getDeviceId, deviceId)
                            .eq(AggregationWindow::isUploaded, false)
                            .orderByAsc(AggregationWindow::getWindowStartMs));

            if (windows.isEmpty()) {
                return 0L;
            }

            log.info("开始上传聚合结果: deviceId={}, count={}, traceId={}",
                    deviceId, windows.size(), ctx.getTraceId());

            List<Long> ids = windows.stream().map(AggregationWindow::getId).collect(Collectors.toList());
            aggregationWindowMapper.update(null, new LambdaUpdateWrapper<AggregationWindow>()
                    .in(AggregationWindow::getId, ids)
                    .set(AggregationWindow::isUploaded, true));

            long dataReduction = calculateDataReduction(windows);
            log.info("聚合结果已上传: deviceId={}, count={}, dataReduction={}%, traceId={}",
                    deviceId, windows.size(), dataReduction, ctx.getTraceId());

            return (long) windows.size();
        });
    }

    private long calculateDataReduction(List<AggregationWindow> windows) {
        long rawCount = windows.stream().mapToLong(AggregationWindow::getRecordCount).sum();
        long aggregatedCount = windows.size();
        if (rawCount == 0) return 0;
        return Math.round((1.0 - (double) aggregatedCount / rawCount) * 100);
    }

    private AggregationResultResponse toAggregationResponse(AggregationWindow window) {
        AggregationResultResponse response = new AggregationResultResponse();
        response.setWindowId(window.getWindowId());
        response.setDeviceId(window.getDeviceId());
        response.setMetricName(window.getMetricName());
        response.setWindowStartMs(window.getWindowStartMs());
        response.setWindowEndMs(window.getWindowEndMs());
        response.setWindowSizeMs(window.getWindowSizeMs());
        response.setRecordCount(window.getRecordCount());
        response.setMinValue(window.getMinValue());
        response.setMaxValue(window.getMaxValue());
        response.setAvgValue(window.getAvgValue());
        response.setSumValue(window.getSumValue());
        response.setVariance(window.getVariance());
        response.setStdDev(window.getStdDev());
        response.setAggregatedAt(window.getAggregatedAt());

        if (window.getPercentiles() != null) {
            response.setPercentiles(JsonUtils.fromJson(window.getPercentiles(), Map.class));
        }

        return response;
    }

    public Mono<Long> getPendingUploadCount(String deviceId, TraceContext ctx) {
        return Mono.fromCallable(() -> aggregationWindowMapper.selectCount(
                new LambdaQueryWrapper<AggregationWindow>()
                        .eq(deviceId != null, AggregationWindow::getDeviceId, deviceId)
                        .eq(AggregationWindow::isUploaded, false)));
    }

    private String generatePointId() {
        return "pt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private String generateWindowId() {
        return "win_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
