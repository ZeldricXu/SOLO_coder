package com.cdcsync.timeseries.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.timeseries.core.CompressionAlgorithm;
import com.cdcsync.timeseries.core.Resolution;
import com.cdcsync.timeseries.core.compressor.TimeSeriesCompressor;
import com.cdcsync.timeseries.core.downsampler.AverageDownsampler;
import com.cdcsync.timeseries.domain.TimeSeriesConfig;
import com.cdcsync.timeseries.domain.TimeSeriesData;
import com.cdcsync.timeseries.mapper.TimeSeriesConfigMapper;
import com.cdcsync.timeseries.mapper.TimeSeriesDataMapper;
import com.cdcsync.timeseries.service.TimeSeriesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TimeSeriesServiceImpl implements TimeSeriesService {

    private final TimeSeriesConfigMapper configMapper;
    private final TimeSeriesDataMapper dataMapper;
    private final Map<String, TimeSeriesCompressor> compressorMap;
    private final AverageDownsampler averageDownsampler;

    @Override
    @Transactional
    public void writeData(String configId, long timestamp, double value, Map<String, String> tags) {
        TimeSeriesConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new BusinessException("Time series config not found: " + configId);
        }
        if (!config.getEnabled()) {
            throw new BusinessException("Time series config is disabled: " + configId);
        }

        TimeSeriesData data = new TimeSeriesData();
        data.setConfigId(configId);
        data.setMetricTs(timestamp);
        data.setValue(value);
        data.setTagsJson(tags != null ? JSON.toJSONString(tags) : null);
        data.setResolution(Resolution.RAW);
        dataMapper.insert(data);

        log.debug("Written data point: configId={}, ts={}, value={}", configId, timestamp, value);
    }

    @Override
    public List<TimeSeriesData> queryData(String configId, long startTime, long endTime, String resolution) {
        TimeSeriesConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new BusinessException("Time series config not found: " + configId);
        }

        Resolution res = Resolution.valueOf(resolution.toUpperCase());

        LambdaQueryWrapper<TimeSeriesData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TimeSeriesData::getConfigId, configId)
                .eq(TimeSeriesData::getResolution, res)
                .ge(TimeSeriesData::getMetricTs, startTime)
                .le(TimeSeriesData::getMetricTs, endTime)
                .orderByAsc(TimeSeriesData::getMetricTs);

        return dataMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public void compressData(String configId) {
        TimeSeriesConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new BusinessException("Time series config not found: " + configId);
        }

        CompressionAlgorithm algorithm = config.getCompressionAlgorithm();
        if (algorithm == null) {
            log.info("No compression algorithm configured for: {}", configId);
            return;
        }

        TimeSeriesCompressor compressor = compressorMap.get(algorithm.name());
        if (compressor == null) {
            throw new BusinessException("Compressor not found for algorithm: " + algorithm);
        }

        LambdaQueryWrapper<TimeSeriesData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TimeSeriesData::getConfigId, configId)
                .eq(TimeSeriesData::getResolution, Resolution.RAW)
                .orderByAsc(TimeSeriesData::getMetricTs);

        List<TimeSeriesData> rawData = dataMapper.selectList(wrapper);
        if (rawData.size() < 100) {
            log.info("Not enough data to compress: {}, size={}", configId, rawData.size());
            return;
        }

        byte[] compressed = compressor.compress(rawData);
        log.info("Compressed data: configId={}, originalSize={}, compressedSize={}",
                configId, rawData.size() * 32, compressed.length);
    }

    @Override
    @Transactional
    public void downsampleData(String configId) {
        TimeSeriesConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new BusinessException("Time series config not found: " + configId);
        }

        long now = Instant.now().toEpochMilli();
        long oneHourMs = 3600000;
        long oneDayMs = 86400000;

        LambdaQueryWrapper<TimeSeriesData> rawWrapper = new LambdaQueryWrapper<>();
        rawWrapper.eq(TimeSeriesData::getConfigId, configId)
                .eq(TimeSeriesData::getResolution, Resolution.RAW)
                .orderByAsc(TimeSeriesData::getMetricTs);

        List<TimeSeriesData> rawData = dataMapper.selectList(rawWrapper);
        if (!rawData.isEmpty()) {
            List<TimeSeriesData> hourlyData = averageDownsampler.downsample(rawData, oneHourMs);
            for (TimeSeriesData point : hourlyData) {
                point.setConfigId(configId);
                point.setResolution(Resolution.H1);
                dataMapper.insert(point);
            }
            log.info("Downsampled to H1: configId={}, count={}", configId, hourlyData.size());

            List<TimeSeriesData> dailyData = averageDownsampler.downsample(rawData, oneDayMs);
            for (TimeSeriesData point : dailyData) {
                point.setConfigId(configId);
                point.setResolution(Resolution.D1);
                dataMapper.insert(point);
            }
            log.info("Downsampled to D1: configId={}, count={}", configId, dailyData.size());
        }
    }

    @Override
    @Transactional
    public void purgeExpiredData(String configId) {
        TimeSeriesConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new BusinessException("Time series config not found: " + configId);
        }

        long now = Instant.now().toEpochMilli();

        if (config.getRawRetentionDays() != null) {
            long cutoff = now - ChronoUnit.DAYS.toMillis(config.getRawRetentionDays());
            LambdaQueryWrapper<TimeSeriesData> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TimeSeriesData::getConfigId, configId)
                    .eq(TimeSeriesData::getResolution, Resolution.RAW)
                    .lt(TimeSeriesData::getMetricTs, cutoff);
            int deleted = dataMapper.delete(wrapper);
            log.info("Purged raw data: configId={}, deleted={}", configId, deleted);
        }

        if (config.getDownsample1hRetentionDays() != null) {
            long cutoff = now - ChronoUnit.DAYS.toMillis(config.getDownsample1hRetentionDays());
            LambdaQueryWrapper<TimeSeriesData> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TimeSeriesData::getConfigId, configId)
                    .eq(TimeSeriesData::getResolution, Resolution.H1)
                    .lt(TimeSeriesData::getMetricTs, cutoff);
            int deleted = dataMapper.delete(wrapper);
            log.info("Purged H1 data: configId={}, deleted={}", configId, deleted);
        }

        if (config.getDownsample1dRetentionDays() != null) {
            long cutoff = now - ChronoUnit.DAYS.toMillis(config.getDownsample1dRetentionDays());
            LambdaQueryWrapper<TimeSeriesData> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TimeSeriesData::getConfigId, configId)
                    .eq(TimeSeriesData::getResolution, Resolution.D1)
                    .lt(TimeSeriesData::getMetricTs, cutoff);
            int deleted = dataMapper.delete(wrapper);
            log.info("Purged D1 data: configId={}, deleted={}", configId, deleted);
        }
    }
}
