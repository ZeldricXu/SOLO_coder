package com.tsdbproxy.timeseries.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class TimeSeriesQueryRequest {

    private String metric;
    private Map<String, String> tags;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String resolution = "raw";
    private String downsampleFunction = "avg";
}
