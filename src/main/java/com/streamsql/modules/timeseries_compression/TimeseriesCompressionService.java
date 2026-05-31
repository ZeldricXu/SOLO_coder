package com.streamsql.modules.timeseries_compression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamsql.common.PageResult;
import com.streamsql.dto.TimeseriesDataDTO;
import com.streamsql.entity.TimeseriesData;
import com.streamsql.mapper.TimeseriesDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

@Slf4j
@Service
@RequiredArgsConstructor
public class TimeseriesCompressionService {

    private final TimeseriesDataMapper timeseriesDataMapper;
    private final ObjectMapper objectMapper;

    @Value("${streamsql.timeseries.compression-enabled:true}")
    private boolean compressionEnabled;

    @Value("${streamsql.timeseries.downsampling-enabled:true}")
    private boolean downsamplingEnabled;

    @Transactional(rollbackFor = Exception.class)
    public TimeseriesData insertData(TimeseriesDataDTO dto) throws JsonProcessingException {
        TimeseriesData data = new TimeseriesData();
        data.setMetricName(dto.getMetricName());
        data.setTimestamp(dto.getTimestamp());
        data.setMetricValue(dto.getMetricValue());
        data.setTags(objectMapper.writeValueAsString(dto.getTags()));
        data.setResolution("raw");
        data.setCompressed(false);

        timeseriesDataMapper.insert(data);
        return data;
    }

    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = "0 0 * * * *")
    public void compressOldData() {
        if (!compressionEnabled) {
            return;
        }

        log.info("Starting time series data compression...");
        
        LocalDateTime cutoffTime = LocalDateTime.now().minus(24, ChronoUnit.HOURS);
        
        List<TimeseriesData> rawData = timeseriesDataMapper.selectList(
                new LambdaQueryWrapper<TimeseriesData>()
                        .eq(TimeseriesData::getResolution, "raw")
                        .eq(TimeseriesData::getCompressed, false)
                        .lt(TimeseriesData::getTimestamp, cutoffTime)
                        .orderByAsc(TimeseriesData::getTimestamp)
                        .last("LIMIT 10000")
        );

        if (rawData.isEmpty()) {
            log.info("No data to compress");
            return;
        }

        Map<String, List<TimeseriesData>> groupedData = new HashMap<>();
        for (TimeseriesData data : rawData) {
            String key = data.getMetricName();
            groupedData.computeIfAbsent(key, k -> new ArrayList<>()).add(data);
        }

        for (Map.Entry<String, List<TimeseriesData>> entry : groupedData.entrySet()) {
            compressAndStore(entry.getKey(), entry.getValue());
        }

        log.info("Compressed {} records", rawData.size());
    }

    @Transactional(rollbackFor = Exception.class)
    public void compressAndStore(String metricName, List<TimeseriesData> dataList) {
        try {
            byte[] compressedData = compressWithDeltaOfDelta(dataList);

            TimeseriesData compressedRecord = new TimeseriesData();
            compressedRecord.setMetricName(metricName);
            compressedRecord.setTimestamp(dataList.get(0).getTimestamp());
            compressedRecord.setMetricValue(0.0);
            compressedRecord.setResolution("raw");
            compressedRecord.setCompressed(true);
            compressedRecord.setCompressedData(compressedData);

            timeseriesDataMapper.insert(compressedRecord);

            List<String> idsToDelete = dataList.stream().map(TimeseriesData::getDataId).toList();
            for (String id : idsToDelete) {
                timeseriesDataMapper.deleteById(id);
            }

        } catch (IOException e) {
            log.error("Failed to compress data for metric: {}", metricName, e);
        }
    }

    private byte[] compressWithDeltaOfDelta(List<TimeseriesData> dataList) throws IOException {
        List<Long> timestamps = new ArrayList<>();
        List<Double> values = new ArrayList<>();

        for (TimeseriesData data : dataList) {
            timestamps.add(java.sql.Timestamp.valueOf(data.getTimestamp()).getTime());
            values.add(data.getMetricValue());
        }

        long[] deltaOfDeltaTimestamps = encodeDeltaOfDelta(timestamps);
        double[] deltaValues = encodeDelta(values);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(8 + deltaOfDeltaTimestamps.length * 8 + deltaValues.length * 8);
        buffer.putInt(dataList.size());
        buffer.putInt(deltaOfDeltaTimestamps.length);
        for (long val : deltaOfDeltaTimestamps) {
            buffer.putLong(val);
        }
        for (double val : deltaValues) {
            buffer.putDouble(val);
        }

        return compress(buffer.array());
    }

    private long[] encodeDeltaOfDelta(List<Long> timestamps) {
        if (timestamps.size() <= 2) {
            return new long[0];
        }

        long[] result = new long[timestamps.size()];
        long prevDelta = 0;
        for (int i = 0; i < timestamps.size(); i++) {
            if (i == 0) {
                result[i] = timestamps.get(i);
            } else if (i == 1) {
                result[i] = timestamps.get(i) - timestamps.get(i - 1);
                prevDelta = result[i];
            } else {
                long delta = timestamps.get(i) - timestamps.get(i - 1);
                result[i] = delta - prevDelta;
                prevDelta = delta;
            }
        }
        return result;
    }

    private double[] encodeDelta(List<Double> values) {
        double[] result = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            if (i == 0) {
                result[i] = values.get(i);
            } else {
                result[i] = values.get(i) - values.get(i - 1);
            }
        }
        return result;
    }

    private byte[] compress(byte[] data) throws IOException {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            baos.write(buffer, 0, count);
        }
        deflater.end();
        return baos.toByteArray();
    }

    private byte[] decompress(byte[] compressedData) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressedData);

        ByteArrayOutputStream baos = new ByteArrayOutputStream(compressedData.length * 2);
        byte[] buffer = new byte[1024];
        while (!inflater.finished()) {
            try {
                int count = inflater.inflate(buffer);
                baos.write(buffer, 0, count);
            } catch (Exception e) {
                break;
            }
        }
        inflater.end();
        return baos.toByteArray();
    }

    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = "0 30 * * * *")
    public void performDownsampling() {
        if (!downsamplingEnabled) {
            return;
        }

        log.info("Starting time series data downsampling...");
        downsampleToResolution("1min", "5min", ChronoUnit.HOURS, 24);
        downsampleToResolution("5min", "15min", ChronoUnit.DAYS, 7);
        downsampleToResolution("15min", "1h", ChronoUnit.DAYS, 30);
        log.info("Downsampling completed");
    }

    @Transactional(rollbackFor = Exception.class)
    public void downsampleToResolution(String sourceResolution, String targetResolution,
                                        ChronoUnit cutoffUnit, long cutoffAmount) {
        LocalDateTime cutoffTime = LocalDateTime.now().minus(cutoffAmount, cutoffUnit);

        List<TimeseriesData> sourceData = timeseriesDataMapper.selectList(
                new LambdaQueryWrapper<TimeseriesData>()
                        .eq(TimeseriesData::getResolution, sourceResolution)
                        .eq(TimeseriesData::getCompressed, false)
                        .lt(TimeseriesData::getTimestamp, cutoffTime)
                        .orderByAsc(TimeseriesData::getTimestamp)
                        .last("LIMIT 5000")
        );

        if (sourceData.isEmpty()) {
            return;
        }

        Map<String, List<TimeseriesData>> groupedByMetric = new HashMap<>();
        for (TimeseriesData data : sourceData) {
            groupedByMetric.computeIfAbsent(data.getMetricName(), k -> new ArrayList<>()).add(data);
        }

        for (Map.Entry<String, List<TimeseriesData>> entry : groupedByMetric.entrySet()) {
            List<TimeseriesData> downsampled = downsample(entry.getValue(), targetResolution);
            for (TimeseriesData data : downsampled) {
                timeseriesDataMapper.insert(data);
            }
        }

        List<String> idsToDelete = sourceData.stream().map(TimeseriesData::getDataId).toList();
        for (String id : idsToDelete) {
            timeseriesDataMapper.deleteById(id);
        }

        log.info("Downsampled {} records from {} to {}", sourceData.size(), sourceResolution, targetResolution);
    }

    private List<TimeseriesData> downsample(List<TimeseriesData> data, String targetResolution) {
        if (data.isEmpty()) {
            return Collections.emptyList();
        }

        Map<LocalDateTime, List<TimeseriesData>> buckets = new LinkedHashMap<>();
        long bucketSizeSeconds = getBucketSizeSeconds(targetResolution);

        for (TimeseriesData point : data) {
            LocalDateTime bucketTime = truncateToBucket(point.getTimestamp(), bucketSizeSeconds);
            buckets.computeIfAbsent(bucketTime, k -> new ArrayList<>()).add(point);
        }

        List<TimeseriesData> result = new ArrayList<>();
        for (Map.Entry<LocalDateTime, List<TimeseriesData>> bucket : buckets.entrySet()) {
            if (bucket.getValue().isEmpty()) continue;

            double sum = bucket.getValue().stream().mapToDouble(TimeseriesData::getMetricValue).sum();
            double avg = sum / bucket.getValue().size();

            TimeseriesData downsampled = new TimeseriesData();
            downsampled.setMetricName(data.get(0).getMetricName());
            downsampled.setTimestamp(bucket.getKey());
            downsampled.setMetricValue(avg);
            downsampled.setTags(data.get(0).getTags());
            downsampled.setResolution(targetResolution);
            downsampled.setCompressed(false);
            result.add(downsampled);
        }

        return result;
    }

    private long getBucketSizeSeconds(String resolution) {
        return switch (resolution) {
            case "5min" -> 300;
            case "15min" -> 900;
            case "1h" -> 3600;
            case "1d" -> 86400;
            default -> 60;
        };
    }

    private LocalDateTime truncateToBucket(LocalDateTime time, long bucketSizeSeconds) {
        long epochSeconds = java.sql.Timestamp.valueOf(time).getTime() / 1000;
        long truncated = (epochSeconds / bucketSizeSeconds) * bucketSizeSeconds;
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(truncated), java.time.ZoneId.systemDefault());
    }

    public PageResult<TimeseriesData> queryData(String metricName, LocalDateTime startTime,
                                                 LocalDateTime endTime, String resolution, int page, int size) {
        LambdaQueryWrapper<TimeseriesData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TimeseriesData::getMetricName, metricName);
        if (startTime != null) {
            wrapper.ge(TimeseriesData::getTimestamp, startTime);
        }
        if (endTime != null) {
            wrapper.le(TimeseriesData::getTimestamp, endTime);
        }
        if (resolution != null) {
            wrapper.eq(TimeseriesData::getResolution, resolution);
        }
        wrapper.orderByAsc(TimeseriesData::getTimestamp);

        IPage<TimeseriesData> pageResult = timeseriesDataMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    public Map<String, Object> getStatistics(String metricName, LocalDateTime startTime, LocalDateTime endTime) {
        List<TimeseriesData> data = timeseriesDataMapper.selectList(
                new LambdaQueryWrapper<TimeseriesData>()
                        .eq(TimeseriesData::getMetricName, metricName)
                        .ge(TimeseriesData::getTimestamp, startTime)
                        .le(TimeseriesData::getTimestamp, endTime)
                        .eq(TimeseriesData::getCompressed, false)
        );

        Map<String, Object> stats = new LinkedHashMap<>();
        if (data.isEmpty()) {
            stats.put("count", 0);
            return stats;
        }

        DoubleSummaryStatistics summary = data.stream()
                .mapToDouble(TimeseriesData::getMetricValue)
                .summaryStatistics();

        stats.put("count", summary.getCount());
        stats.put("min", summary.getMin());
        stats.put("max", summary.getMax());
        stats.put("avg", summary.getAverage());
        stats.put("sum", summary.getSum());

        return stats;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteData(String metricName, LocalDateTime beforeTime) {
        timeseriesDataMapper.delete(
                new LambdaQueryWrapper<TimeseriesData>()
                        .eq(TimeseriesData::getMetricName, metricName)
                        .lt(TimeseriesData::getTimestamp, beforeTime)
        );
    }
}
