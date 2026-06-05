package com.datateam.loganalyzer.aggregator;

import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.LogLevel;
import com.datateam.loganalyzer.model.TimeSeriesPoint;
import com.datateam.loganalyzer.util.TimeUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class TimeSeriesAggregator {

    private final TimeUtils.Granularity granularity;
    private final long windowSeconds;
    private final long slideSeconds;
    private final Map<Instant, TimeSeriesPoint> windows;
    private final boolean slidingWindow;

    public TimeSeriesAggregator(TimeUtils.Granularity granularity) {
        this(granularity, false);
    }

    public TimeSeriesAggregator(TimeUtils.Granularity granularity, boolean slidingWindow) {
        this.granularity = granularity;
        this.windowSeconds = TimeUtils.getGranularitySeconds(granularity);
        this.slideSeconds = this.windowSeconds;
        this.slidingWindow = slidingWindow;
        this.windows = new TreeMap<>();
    }

    public TimeSeriesAggregator(long windowSeconds, long slideSeconds) {
        this.granularity = TimeUtils.Granularity.MINUTE;
        this.windowSeconds = windowSeconds;
        this.slideSeconds = slideSeconds;
        this.slidingWindow = true;
        this.windows = new TreeMap<>();
    }

    public void add(LogEvent event) {
        if (event == null || event.getTimestamp() == null) {
            return;
        }

        Instant eventTime = event.getTimestamp();

        if (slidingWindow) {
            addToSlidingWindows(eventTime, event);
        } else {
            addToTumblingWindow(eventTime, event);
        }
    }

    private void addToTumblingWindow(Instant eventTime, LogEvent event) {
        Instant windowStart = TimeUtils.truncateToGranularity(eventTime, granularity);
        TimeSeriesPoint point = windows.computeIfAbsent(windowStart, this::createPoint);
        updatePoint(point, event);
    }

    private void addToSlidingWindows(Instant eventTime, LogEvent event) {
        long epochSecond = eventTime.getEpochSecond();
        long windowStartEpoch = (epochSecond / slideSeconds) * slideSeconds;
        long firstWindowStart = windowStartEpoch - windowSeconds + slideSeconds;

        for (long start = firstWindowStart; start <= windowStartEpoch; start += slideSeconds) {
            Instant windowStart = Instant.ofEpochSecond(start);
            Instant windowEnd = Instant.ofEpochSecond(start + windowSeconds);
            if (eventTime.compareTo(windowStart) >= 0 && eventTime.compareTo(windowEnd) < 0) {
                TimeSeriesPoint point = windows.computeIfAbsent(windowStart,
                    k -> createPoint(windowStart, windowEnd));
                updatePoint(point, event);
            }
        }
    }

    private TimeSeriesPoint createPoint(Instant windowStart) {
        Instant windowEnd = windowStart.plusSeconds(windowSeconds);
        return createPoint(windowStart, windowEnd);
    }

    private TimeSeriesPoint createPoint(Instant windowStart, Instant windowEnd) {
        TimeSeriesPoint point = new TimeSeriesPoint();
        point.setWindowStart(windowStart);
        point.setWindowEnd(windowEnd);
        point.setDurationSeconds(windowSeconds);
        return point;
    }

    private void updatePoint(TimeSeriesPoint point, LogEvent event) {
        point.incrementTotal();
        point.incrementLevel(event.getLevel() != null ? event.getLevel() : LogLevel.UNKNOWN);
        point.incrementService(event.getService());
        if (event.getLevel() == LogLevel.ERROR || event.getLevel() == LogLevel.FATAL) {
            point.incrementServiceError(event.getService());
        }
        String errorType = event.extractErrorType();
        if (errorType != null) {
            point.incrementErrorType(errorType);
        } else if (event.getErrorType() != null) {
            point.incrementErrorType(event.getErrorType());
        }
    }

    public List<TimeSeriesPoint> getTimeSeries() {
        List<TimeSeriesPoint> points = new ArrayList<>(windows.values());
        for (TimeSeriesPoint point : points) {
            point.calculateRates();
        }
        points.sort(Comparator.comparing(TimeSeriesPoint::getWindowStart));
        return points;
    }

    public List<TimeSeriesPoint> getTimeSeries(String metric) {
        List<TimeSeriesPoint> points = getTimeSeries();
        return points;
    }

    public Map<String, List<TimeSeriesPoint>> getTimeSeriesByService() {
        Map<String, List<TimeSeriesPoint>> result = new LinkedHashMap<>();
        List<TimeSeriesPoint> allPoints = getTimeSeries();

        for (TimeSeriesPoint point : allPoints) {
            for (Map.Entry<String, Long> entry : point.getServiceCounts().entrySet()) {
                String service = entry.getKey();
                result.computeIfAbsent(service, k -> new ArrayList<>());
            }
        }

        return result;
    }

    public Map<LogLevel, List<Double>> getLevelTimeSeries() {
        Map<LogLevel, List<Double>> result = new LinkedHashMap<>();
        List<TimeSeriesPoint> points = getTimeSeries();

        for (LogLevel level : LogLevel.values()) {
            List<Double> values = new ArrayList<>();
            for (TimeSeriesPoint point : points) {
                values.add((double) point.getLevelCount(level));
            }
            if (values.stream().anyMatch(v -> v > 0)) {
                result.put(level, values);
            }
        }

        return result;
    }

    public List<TimeSeriesPoint> aggregateByLevel(LogLevel level) {
        return getTimeSeries();
    }

    public long getTotalCount() {
        return windows.values().stream().mapToLong(TimeSeriesPoint::getTotalCount).sum();
    }

    public long getErrorCount() {
        return windows.values().stream().mapToLong(TimeSeriesPoint::getErrorCount).sum();
    }

    public long getWarnCount() {
        return windows.values().stream().mapToLong(TimeSeriesPoint::getWarnCount).sum();
    }

    public Instant getStartTime() {
        return windows.isEmpty() ? null : windows.keySet().iterator().next();
    }

    public Instant getEndTime() {
        if (windows.isEmpty()) return null;
        Instant last = null;
        for (Instant key : windows.keySet()) {
            last = key;
        }
        return last != null ? last.plusSeconds(windowSeconds) : null;
    }

    public Map<String, Long> getServiceTotals() {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (TimeSeriesPoint point : windows.values()) {
            for (Map.Entry<String, Long> entry : point.getServiceCounts().entrySet()) {
                totals.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }
        return totals;
    }

    public Map<String, Long> getErrorTypeTotals() {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (TimeSeriesPoint point : windows.values()) {
            for (Map.Entry<String, Long> entry : point.getErrorTypeCounts().entrySet()) {
                totals.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }
        return totals;
    }

    public Map<String, Long> getServiceErrorTotals() {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (TimeSeriesPoint point : windows.values()) {
            for (Map.Entry<String, Long> entry : point.getServiceErrorCounts().entrySet()) {
                totals.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }
        return totals;
    }

    public TimeUtils.Granularity getGranularity() {
        return granularity;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public long getSlideSeconds() {
        return slideSeconds;
    }
}
