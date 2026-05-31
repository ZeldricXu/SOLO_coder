package com.tsdbproxy.timeseries.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesPoint {

    private LocalDateTime timestamp;
    private Double value;
}
