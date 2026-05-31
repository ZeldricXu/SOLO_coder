package com.parking.platform.monitoring.service;

import com.parking.platform.common.util.IdGenerator;
import com.parking.platform.monitoring.entity.MetricSnapshot;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.search.Search;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class MonitoringService {

    private final MeterRegistry meterRegistry;
    private final Map<String, MetricSnapshot> snapshots = new ConcurrentHashMap<>();

    public MonitoringService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Counter getOrCreateCounter(String name, String... tags) {
        return Counter.builder(name)
                .tags(tags)
                .register(meterRegistry);
    }

    public void incrementCounter(String name, String... tags) {
        getOrCreateCounter(name, tags).increment();
    }

    public void incrementCounter(String name, double amount, String... tags) {
        getOrCreateCounter(name, tags).increment(amount);
    }

    public <T> Gauge gauge(String name, T object, ToDoubleFunction<T> function, String... tags) {
        return Gauge.builder(name, object, function)
                .tags(tags)
                .register(meterRegistry);
    }

    public Timer getOrCreateTimer(String name, String... tags) {
        return Timer.builder(name)
                .tags(tags)
                .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                .register(meterRegistry);
    }

    public void recordTimer(String name, long amount, TimeUnit unit, String... tags) {
        getOrCreateTimer(name, tags).record(amount, unit);
    }

    public void recordTimer(String name, Duration duration, String... tags) {
        getOrCreateTimer(name, tags).record(duration);
    }

    public DistributionSummary getOrCreateSummary(String name, String... tags) {
        return DistributionSummary.builder(name)
                .tags(tags)
                .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                .register(meterRegistry);
    }

    public void recordSummary(String name, double amount, String... tags) {
        getOrCreateSummary(name, tags).record(amount);
    }

    public MetricSnapshot createSnapshot(String name) {
        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setId(IdGenerator.generate("snap"));
        snapshot.setName(name);
        snapshot.setTimestamp(System.currentTimeMillis());

        Map<String, Object> details = new HashMap<>();
        Search search = Search.in(meterRegistry);

        List<Counter> counters = search.counters();
        List<Gauge> gauges = search.gauges();
        List<Timer> timers = search.timers();
        List<DistributionSummary> summaries = search.summaries();

        double totalRequests = counters.stream().mapToDouble(Counter::count).sum();
        details.put("totalRequests", totalRequests);

        Map<String, Double> gaugesMap = new HashMap<>();
        for (Gauge gauge : gauges) {
            gaugesMap.put(gauge.getId().getName(), gauge.value());
        }
        details.put("gauges", gaugesMap);

        Map<String, Object> timersMap = new HashMap<>();
        for (Timer timer : timers) {
            Map<String, Object> timerDetails = new HashMap<>();
            timerDetails.put("count", timer.count());
            timerDetails.put("mean_ms", timer.mean(TimeUnit.MILLISECONDS));
            timerDetails.put("max_ms", timer.max(TimeUnit.MILLISECONDS));
            timerDetails.put("total_ms", timer.totalTime(TimeUnit.MILLISECONDS));
            timersMap.put(timer.getId().getName(), timerDetails);
        }
        details.put("timers", timersMap);

        if (!timers.isEmpty()) {
            Timer firstTimer = timers.iterator().next();
            snapshot.setType(MetricSnapshot.MetricType.TIMER);
            snapshot.setValue(firstTimer.mean(TimeUnit.MILLISECONDS));
        } else if (!counters.isEmpty()) {
            snapshot.setType(MetricSnapshot.MetricType.COUNTER);
            snapshot.setValue(totalRequests);
        } else {
            snapshot.setType(MetricSnapshot.MetricType.GAUGE);
            snapshot.setValue(0.0);
        }

        snapshot.setDetails(details);
        snapshots.put(snapshot.getId(), snapshot);
        return snapshot;
    }

    public List<MetricSnapshot> getSnapshots() {
        return List.copyOf(snapshots.values());
    }

    public Map<String, Object> getMetricsSummary() {
        Map<String, Object> summary = new HashMap<>();

        long counterCount = meterRegistry.getMeters().stream()
                .filter(m -> m.getId().getType() == Meter.Type.COUNTER)
                .count();
        long gaugeCount = meterRegistry.getMeters().stream()
                .filter(m -> m.getId().getType() == Meter.Type.GAUGE)
                .count();
        long timerCount = meterRegistry.getMeters().stream()
                .filter(m -> m.getId().getType() == Meter.Type.TIMER)
                .count();
        long summaryCount = meterRegistry.getMeters().stream()
                .filter(m -> m.getId().getType() == Meter.Type.DISTRIBUTION_SUMMARY)
                .count();

        summary.put("totalMeters", meterRegistry.getMeters().size());
        summary.put("counterCount", counterCount);
        summary.put("gaugeCount", gaugeCount);
        summary.put("timerCount", timerCount);
        summary.put("summaryCount", summaryCount);

        List<Map<String, Object>> meterList = new ArrayList<>();
        for (Meter meter : meterRegistry.getMeters()) {
            Map<String, Object> meterInfo = new HashMap<>();
            meterInfo.put("name", meter.getId().getName());
            meterInfo.put("type", meter.getId().getType().name());
            meterInfo.put("tags", meter.getId().getTags());
            meterList.add(meterInfo);
        }
        summary.put("meters", meterList);

        return summary;
    }

    public MetricSnapshot recordPerformanceMetric(String operation, long durationMs, boolean success) {
        String status = success ? "success" : "error";
        incrementCounter("operation.total", "status", status);
        recordTimer("operation.duration", durationMs, TimeUnit.MILLISECONDS, "operation", operation, "status", status);

        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setId(IdGenerator.generate("perf"));
        snapshot.setName("performance." + operation);
        snapshot.setType(MetricSnapshot.MetricType.TIMER);
        snapshot.setValue((double) durationMs);
        snapshot.setTimestamp(System.currentTimeMillis());
        snapshot.getDimensions().put("operation", operation);
        snapshot.getDimensions().put("status", status);
        snapshot.getDetails().put("durationMs", durationMs);
        snapshot.getDetails().put("success", success);

        snapshots.put(snapshot.getId(), snapshot);
        return snapshot;
    }
}
