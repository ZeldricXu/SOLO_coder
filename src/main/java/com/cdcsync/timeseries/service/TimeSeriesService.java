package com.cdcsync.timeseries.service;

import com.cdcsync.timeseries.domain.TimeSeriesData;

import java.util.List;
import java.util.Map;

public interface TimeSeriesService {

    void writeData(String configId, long timestamp, double value, Map<String, String> tags);

    List<TimeSeriesData> queryData(String configId, long startTime, long endTime, String resolution);

    void compressData(String configId);

    void downsampleData(String configId);

    void purgeExpiredData(String configId);
}
