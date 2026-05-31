package com.tsdbproxy.timeseries.downsample;

import com.tsdbproxy.timeseries.dto.TimeSeriesPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class Downsampler {

    public List<TimeSeriesPoint> downsample(List<TimeSeriesPoint> data, String resolution, String function) {
        if (data == null || data.isEmpty()) {
            return data;
        }

        Duration window = getWindowDuration(resolution);
        if (window == null) {
            return data;
        }

        log.info("执行降采样: 分辨率={}, 聚合函数={}, 原始点数={}", resolution, function, data.size());

        List<TimeSeriesPoint> result = new ArrayList<>();
        List<TimeSeriesPoint> windowPoints = new ArrayList<>();
        LocalDateTime windowStart = data.get(0).getTimestamp();
        LocalDateTime windowEnd = windowStart.plus(window);

        for (TimeSeriesPoint point : data) {
            if (point.getTimestamp().isBefore(windowEnd)) {
                windowPoints.add(point);
            } else {
                if (!windowPoints.isEmpty()) {
                    result.add(aggregate(windowPoints, function, windowStart));
                }

                while (!point.getTimestamp().isBefore(windowEnd)) {
                    windowStart = windowEnd;
                    windowEnd = windowEnd.plus(window);
                }

                windowPoints.clear();
                windowPoints.add(point);
            }
        }

        if (!windowPoints.isEmpty()) {
            result.add(aggregate(windowPoints, function, windowStart));
        }

        log.info("降采样完成: 结果点数={}", result.size());
        return result;
    }

    private Duration getWindowDuration(String resolution) {
        return switch (resolution.toLowerCase()) {
            case "hourly" -> Duration.ofHours(1);
            case "daily" -> Duration.ofDays(1);
            case "weekly" -> Duration.ofDays(7);
            case "monthly" -> Duration.ofDays(30);
            default -> null;
        };
    }

    private TimeSeriesPoint aggregate(List<TimeSeriesPoint> points, String function, LocalDateTime timestamp) {
        double result;

        result = switch (function.toLowerCase()) {
            case "sum" -> points.stream().mapToDouble(TimeSeriesPoint::getValue).sum();
            case "min" -> points.stream().mapToDouble(TimeSeriesPoint::getValue).min().orElse(0);
            case "max" -> points.stream().mapToDouble(TimeSeriesPoint::getValue).max().orElse(0);
            case "count" -> points.size();
            default -> points.stream().mapToDouble(TimeSeriesPoint::getValue).average().orElse(0);
        };

        return new TimeSeriesPoint(timestamp, result);
    }
}
