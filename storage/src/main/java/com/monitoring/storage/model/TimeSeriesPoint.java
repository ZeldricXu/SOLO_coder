package com.monitoring.storage.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Map;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesPoint {

    private String metric;

    private double value;

    private long timestamp;

    private Map<String, String> tags;

    public Instant getTimestampAsInstant() {
        return Instant.ofEpochMilli(timestamp);
    }
}
