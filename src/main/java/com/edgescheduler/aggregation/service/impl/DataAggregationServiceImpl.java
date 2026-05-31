package com.edgescheduler.aggregation.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edgescheduler.aggregation.dto.DataCollectRequest;
import com.edgescheduler.aggregation.dto.DataStreamDTO;
import com.edgescheduler.aggregation.entity.DataAggregationResult;
import com.edgescheduler.aggregation.entity.DataStream;
import com.edgescheduler.aggregation.mapper.DataAggregationResultMapper;
import com.edgescheduler.aggregation.mapper.DataStreamMapper;
import com.edgescheduler.aggregation.service.DataAggregationService;
import com.edgescheduler.common.exception.BusinessException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataAggregationServiceImpl implements DataAggregationService {

    private final DataStreamMapper streamMapper;
    private final DataAggregationResultMapper resultMapper;
    private final MeterRegistry meterRegistry;

    private final Map<String, List<Map<String, Object>>> rawDataBuffer = new ConcurrentHashMap<>();
    private final Cache<String, LocalDateTime> windowCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    @Override
    @Transactional
    public DataStreamDTO createDataStream(DataStreamDTO dto) {
        String streamId = "stream_" + IdUtil.getSnowflakeNextIdStr();
        DataStream stream = new DataStream();
        BeanUtils.copyProperties(dto, stream);
        stream.setStreamId(streamId);
        if (stream.getEnabled() == null) {
            stream.setEnabled(1);
        }
        if (stream.getAggregationType() == null) {
            stream.setAggregationType(DataStream.AggregationType.NONE);
        }
        if (stream.getAggregationWindow() == null) {
            stream.setAggregationWindow("1m");
        }

        streamMapper.insert(stream);
        meterRegistry.counter("datastream.create.total").increment();
        log.info("Data stream created: {}", streamId);

        return convertToDTO(stream);
    }

    @Override
    public DataStreamDTO getDataStream(String streamId) {
        DataStream stream = getStreamEntity(streamId);
        return convertToDTO(stream);
    }

    @Override
    public IPage<DataStreamDTO> listDataStreams(Page<DataStream> page, String deviceKey, Integer enabled) {
        LambdaQueryWrapper<DataStream> wrapper = new LambdaQueryWrapper<>();
        if (deviceKey != null) {
            wrapper.eq(DataStream::getDeviceKey, deviceKey);
        }
        if (enabled != null) {
            wrapper.eq(DataStream::getEnabled, enabled);
        }
        wrapper.orderByDesc(DataStream::getCreatedAt);

        return streamMapper.selectPage(page, wrapper)
                .convert(this::convertToDTO);
    }

    @Override
    @Transactional
    public DataStreamDTO updateDataStream(String streamId, DataStreamDTO dto) {
        DataStream stream = getStreamEntity(streamId);

        if (dto.getStreamName() != null) {
            stream.setStreamName(dto.getStreamName());
        }
        if (dto.getDataType() != null) {
            stream.setDataType(dto.getDataType());
        }
        if (dto.getAggregationType() != null) {
            stream.setAggregationType(dto.getAggregationType());
        }
        if (dto.getAggregationWindow() != null) {
            stream.setAggregationWindow(dto.getAggregationWindow());
        }
        if (dto.getFieldsConfig() != null) {
            stream.setFieldsConfig(dto.getFieldsConfig());
        }
        if (dto.getEnabled() != null) {
            stream.setEnabled(dto.getEnabled());
        }

        streamMapper.updateById(stream);
        log.info("Data stream updated: {}", streamId);

        return convertToDTO(stream);
    }

    @Override
    @Transactional
    public void deleteDataStream(String streamId) {
        DataStream stream = getStreamEntity(streamId);
        streamMapper.deleteById(stream.getId());
        rawDataBuffer.remove(streamId);
        log.info("Data stream deleted: {}", streamId);
    }

    @Override
    public void collectData(DataCollectRequest request) {
        DataStream stream = getStreamEntity(request.getStreamId());
        if (stream.getEnabled() != 1) {
            throw BusinessException.badRequest("Data stream is disabled: " + request.getStreamId());
        }

        Map<String, Object> dataPoint = new HashMap<>(request.getData());
        dataPoint.put("_timestamp", request.getTimestamp() != null ?
                request.getTimestamp() : LocalDateTime.now());
        dataPoint.put("_deviceKey", request.getDeviceKey());

        if (DataStream.AggregationType.NONE.equals(stream.getAggregationType())) {
            saveRawDataAsResult(stream, dataPoint);
        } else {
            rawDataBuffer.computeIfAbsent(request.getStreamId(), k -> new CopyOnWriteArrayList<>())
                    .add(dataPoint);
        }

        meterRegistry.counter("datastream.collect.total",
                "streamId", request.getStreamId()).increment();
        log.debug("Data collected for stream: {}, data points: {}",
                request.getStreamId(), rawDataBuffer.getOrDefault(request.getStreamId(), Collections.emptyList()).size());
    }

    private void saveRawDataAsResult(DataStream stream, Map<String, Object> dataPoint) {
        String resultId = "result_" + IdUtil.getSnowflakeNextIdStr();
        DataAggregationResult result = new DataAggregationResult();
        result.setResultId(resultId);
        result.setStreamId(stream.getStreamId());
        result.setDeviceKey(stream.getDeviceKey());
        result.setAggregationType(DataStream.AggregationType.NONE);
        result.setWindowStart(LocalDateTime.now());
        result.setWindowEnd(LocalDateTime.now());
        result.setMetrics(dataPoint);
        result.setSampleCount(1);
        result.setUploaded(0);
        resultMapper.insert(result);
    }

    @Override
    @Scheduled(fixedDelayString = "${edge.scheduler.aggregation.interval:60000}")
    @Transactional
    public void processAggregation() {
        List<DataStream> streams = streamMapper.selectAllAggregationStreams();

        for (DataStream stream : streams) {
            try {
                aggregateStreamData(stream);
            } catch (Exception e) {
                log.error("Aggregation failed for stream: {}", stream.getStreamId(), e);
            }
        }

        uploadAggregationResults();
    }

    private void aggregateStreamData(DataStream stream) {
        List<Map<String, Object>> buffer = rawDataBuffer.get(stream.getStreamId());
        if (buffer == null || buffer.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Duration windowDuration = parseWindowDuration(stream.getAggregationWindow());
        LocalDateTime currentWindowStart = windowCache.getIfPresent(stream.getStreamId());

        if (currentWindowStart == null) {
            currentWindowStart = truncateToWindow(now, windowDuration);
            windowCache.put(stream.getStreamId(), currentWindowStart);
        }

        LocalDateTime windowEnd = currentWindowStart.plus(windowDuration);

        if (!now.isBefore(windowEnd) || buffer.size() >= 1000) {
            List<Map<String, Object>> windowData = new ArrayList<>(buffer);
            buffer.clear();

            if (!windowData.isEmpty()) {
                Map<String, Object> metrics = calculateAggregation(
                        stream.getAggregationType(), windowData, stream.getFieldsConfig());

                String resultId = "result_" + IdUtil.getSnowflakeNextIdStr();
                DataAggregationResult result = new DataAggregationResult();
                result.setResultId(resultId);
                result.setStreamId(stream.getStreamId());
                result.setDeviceKey(stream.getDeviceKey());
                result.setAggregationType(stream.getAggregationType());
                result.setWindowStart(currentWindowStart);
                result.setWindowEnd(windowEnd);
                result.setMetrics(metrics);
                result.setSampleCount(windowData.size());
                result.setUploaded(0);
                resultMapper.insert(result);

                stream.setLastAggregatedAt(now);
                streamMapper.updateById(stream);

                meterRegistry.counter("datastream.aggregation.total",
                        "streamId", stream.getStreamId(),
                        "type", stream.getAggregationType()).increment();
                log.info("Aggregation completed for stream: {}, samples: {}, window: {} - {}",
                        stream.getStreamId(), windowData.size(), currentWindowStart, windowEnd);
            }

            windowCache.put(stream.getStreamId(), truncateToWindow(now, windowDuration));
        }
    }

    @Override
    public Map<String, Object> calculateAggregation(String type,
                                                     List<Map<String, Object>> data,
                                                     List<Map<String, Object>> fieldsConfig) {
        if (data == null || data.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> fields = extractFields(fieldsConfig);
        Map<String, Object> result = new HashMap<>();

        for (String field : fields) {
            List<Double> values = data.stream()
                    .map(d -> d.get(field))
                    .filter(v -> v != null)
                    .map(v -> {
                        if (v instanceof Number) {
                            return ((Number) v).doubleValue();
                        }
                        try {
                            return Double.parseDouble(v.toString());
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();

            if (values.isEmpty()) {
                continue;
            }

            switch (type) {
                case DataStream.AggregationType.AVG -> {
                    double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                    result.put(field + "_avg", round(avg));
                }
                case DataStream.AggregationType.SUM -> {
                    double sum = values.stream().mapToDouble(Double::doubleValue).sum();
                    result.put(field + "_sum", round(sum));
                }
                case DataStream.AggregationType.COUNT -> {
                    result.put(field + "_count", values.size());
                }
                case DataStream.AggregationType.MIN -> {
                    double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
                    result.put(field + "_min", round(min));
                }
                case DataStream.AggregationType.MAX -> {
                    double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
                    result.put(field + "_max", round(max));
                }
                case DataStream.AggregationType.FIRST -> {
                    result.put(field + "_first", values.get(0));
                }
                case DataStream.AggregationType.LAST -> {
                    result.put(field + "_last", values.get(values.size() - 1));
                }
                default -> {
                    result.put(field, values);
                }
            }
        }

        result.put("_sampleCount", data.size());
        result.put("_aggregationType", type);

        return result;
    }

    private List<String> extractFields(List<Map<String, Object>> fieldsConfig) {
        if (fieldsConfig == null || fieldsConfig.isEmpty()) {
            return List.of("value");
        }
        return fieldsConfig.stream()
                .map(f -> (String) f.get("field"))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<DataAggregationResult> getAggregationResults(String streamId, int limit) {
        return resultMapper.selectByStreamId(streamId, limit);
    }

    @Override
    public List<DataAggregationResult> getAggregationResultsByTimeRange(
            String streamId, LocalDateTime startTime, LocalDateTime endTime) {
        return resultMapper.selectByStreamIdAndTimeRange(streamId, startTime, endTime);
    }

    @Override
    @Transactional
    public void uploadAggregationResults() {
        List<DataAggregationResult> pending = resultMapper.selectPendingUpload(100);
        if (pending.isEmpty()) {
            return;
        }

        int uploaded = 0;
        for (DataAggregationResult result : pending) {
            try {
                simulateCloudUpload(result);
                resultMapper.markAsUploaded(result.getResultId(), LocalDateTime.now());
                uploaded++;
            } catch (Exception e) {
                log.error("Failed to upload aggregation result: {}", result.getResultId(), e);
            }
        }

        if (uploaded > 0) {
            meterRegistry.counter("datastream.upload.total").increment(uploaded);
            log.info("Uploaded {} aggregation results to cloud", uploaded);
        }
    }

    private void simulateCloudUpload(DataAggregationResult result) {
        log.debug("Uploading aggregation result: {} to cloud, size: {} bytes",
                result.getResultId(), result.getMetrics().toString().length());
    }

    @Override
    public Map<String, Object> getAggregationStatistics(String streamId) {
        List<DataAggregationResult> results = resultMapper.selectByStreamId(streamId, 100);

        long totalResults = results.size();
        long uploadedResults = results.stream().filter(r -> r.getUploaded() == 1).count();
        long pendingResults = totalResults - uploadedResults;
        int totalSamples = results.stream().mapToInt(DataAggregationResult::getSampleCount).sum();

        return Map.of(
                "streamId", streamId,
                "totalResults", totalResults,
                "uploadedResults", uploadedResults,
                "pendingResults", pendingResults,
                "totalSamples", totalSamples,
                "avgSamplesPerWindow", totalResults > 0 ? totalSamples / totalResults : 0,
                "uploadProgress", totalResults > 0 ?
                        round((double) uploadedResults / totalResults * 100) + "%" : "0%",
                "bandwidthSaved", calculateBandwidthSaved(totalSamples, totalResults)
        );
    }

    private String calculateBandwidthSaved(int rawSamples, long aggregatedResults) {
        if (rawSamples == 0) return "0%";
        int rawSize = rawSamples * 512;
        int aggregatedSize = (int) aggregatedResults * 256;
        double saved = (1.0 - (double) aggregatedSize / rawSize) * 100;
        return round(saved) + "%";
    }

    private Duration parseWindowDuration(String window) {
        return switch (window) {
            case "1m" -> Duration.ofMinutes(1);
            case "5m" -> Duration.ofMinutes(5);
            case "15m" -> Duration.ofMinutes(15);
            case "1h" -> Duration.ofHours(1);
            case "1d" -> Duration.ofDays(1);
            default -> Duration.ofMinutes(1);
        };
    }

    private LocalDateTime truncateToWindow(LocalDateTime time, Duration window) {
        long windowSeconds = window.getSeconds();
        long epochSeconds = time.toEpochSecond(java.time.ZoneOffset.UTC);
        long truncatedSeconds = (epochSeconds / windowSeconds) * windowSeconds;
        return LocalDateTime.ofEpochSecond(truncatedSeconds, 0, java.time.ZoneOffset.UTC);
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private DataStream getStreamEntity(String streamId) {
        DataStream stream = streamMapper.selectByStreamId(streamId);
        if (stream == null) {
            throw BusinessException.notFound("Data stream not found: " + streamId);
        }
        return stream;
    }

    private DataStreamDTO convertToDTO(DataStream stream) {
        DataStreamDTO dto = new DataStreamDTO();
        BeanUtils.copyProperties(stream, dto);
        return dto;
    }
}
