package com.datapipeline.monitoring.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistogramStats {

    public static final HistogramStats EMPTY = HistogramStats.builder()
            .count(0)
            .min(0)
            .max(0)
            .avg(0.0)
            .p50(0)
            .p95(0)
            .p99(0)
            .sum(0)
            .build();

    private int count;
    private long min;
    private long max;
    private double avg;
    private long p50;
    private long p95;
    private long p99;
    private long sum;

}
