package com.solocoder.dns.monitoring.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solocoder.dns.common.entity.StatsSnapshot;
import com.solocoder.dns.common.model.PageResult;
import com.solocoder.dns.common.util.IdGenerator;
import com.solocoder.dns.common.util.JsonUtils;
import com.solocoder.dns.persistence.entity.StatsSnapshotPO;
import com.solocoder.dns.persistence.mapper.StatsSnapshotMapper;
import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsService {
    private final MeterRegistry meterRegistry;
    private final StatsSnapshotMapper snapshotMapper;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, Gauge> gauges = new ConcurrentHashMap<>();

    public void incrementCounter(String name, String... tags) {
        Counter counter = counters.computeIfAbsent(name, k -> Counter.builder(name)
                .tags(tags)
                .register(meterRegistry));
        counter.increment();
    }

    public void recordTimer(String name, Runnable runnable, String... tags) {
        Timer timer = timers.computeIfAbsent(name, k -> Timer.builder(name)
                .tags(tags)
                .register(meterRegistry));
        timer.record(runnable);
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopTimer(Timer.Sample sample, String name, String... tags) {
        Timer timer = timers.computeIfAbsent(name, k -> Timer.builder(name)
                .tags(tags)
                .register(meterRegistry));
        sample.stop(timer);
    }

    public void recordGauge(String name, double value, String... tags) {
        Gauge.builder(name, () -> value)
                .tags(tags)
                .strongReference(true)
                .register(meterRegistry);
    }

    public StatsSnapshot createSnapshot(Map<String, Object> metrics, Map<String, String> dimensions) {
        StatsSnapshot snapshot = new StatsSnapshot();
        snapshot.setSnapshotId(IdGenerator.generateSnapshotId());
        snapshot.setTimestamp(LocalDateTime.now());
        snapshot.setMetrics(metrics);
        snapshot.setDimensions(dimensions);
        snapshotMapper.insert(toPO(snapshot));
        log.debug("Stats snapshot created: {}", snapshot.getSnapshotId());
        return snapshot;
    }

    public StatsSnapshot getSnapshot(String snapshotId) {
        StatsSnapshotPO po = snapshotMapper.selectById(snapshotId);
        if (po == null) {
            return null;
        }
        return toDomain(po);
    }

    public PageResult<StatsSnapshot> listSnapshots(int page, int size) {
        LambdaQueryWrapper<StatsSnapshotPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(StatsSnapshotPO::getTimestamp);
        Page<StatsSnapshotPO> poPage = snapshotMapper.selectPage(new Page<>(page, size), wrapper);
        List<StatsSnapshot> items = poPage.getRecords().stream().map(this::toDomain).collect(Collectors.toList());
        return new PageResult<>(items, poPage.getTotal(), page, size);
    }

    public Map<String, Object> getCurrentMetrics() {
        Map<String, Object> metrics = new ConcurrentHashMap<>();
        meterRegistry.getMeters().forEach(meter -> {
            String name = meter.getId().getName();
            if (meter instanceof Counter) {
                metrics.put(name, ((Counter) meter).count());
            } else if (meter instanceof Gauge) {
                metrics.put(name, ((Gauge) meter).value());
            } else if (meter instanceof Timer) {
                Timer timer = (Timer) meter;
                metrics.put(name + ".count", timer.count());
                metrics.put(name + ".mean", timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
                metrics.put(name + ".max", timer.max(java.util.concurrent.TimeUnit.MILLISECONDS));
            }
        });
        return metrics;
    }

    private StatsSnapshotPO toPO(StatsSnapshot snapshot) {
        StatsSnapshotPO po = new StatsSnapshotPO();
        po.setSnapshotId(snapshot.getSnapshotId());
        po.setTimestamp(snapshot.getTimestamp());
        po.setMetrics(JsonUtils.toJson(snapshot.getMetrics()));
        po.setDimensions(JsonUtils.toJson(snapshot.getDimensions()));
        return po;
    }

    @SuppressWarnings("unchecked")
    private StatsSnapshot toDomain(StatsSnapshotPO po) {
        StatsSnapshot snapshot = new StatsSnapshot();
        snapshot.setSnapshotId(po.getSnapshotId());
        snapshot.setTimestamp(po.getTimestamp());
        snapshot.setMetrics(po.getMetrics() != null ? JsonUtils.fromJson(po.getMetrics(), Map.class) : null);
        snapshot.setDimensions(po.getDimensions() != null ? JsonUtils.fromJson(po.getDimensions(), Map.class) : null);
        return snapshot;
    }
}
