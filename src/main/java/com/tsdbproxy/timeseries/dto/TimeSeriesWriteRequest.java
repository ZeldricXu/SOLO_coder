package com.tsdbproxy.timeseries.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class TimeSeriesWriteRequest {

    private String metric;
    private Map<String, String> tags;
    private LocalDateTime timestamp;
    private Double value;
    private String compressionType = "gorilla";
}
