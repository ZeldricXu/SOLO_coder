package com.scheduler.data.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.scheduler.data.cache.CacheManager;
import com.scheduler.persistence.entity.MetricsSnapshot;
import com.scheduler.persistence.mapper.MetricsSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MetricsSnapshotRepository {

    private final MetricsSnapshotMapper metricsSnapshotMapper;
    private final CacheManager cacheManager;

    private static final String CACHE_PREFIX = "metrics:";
    private static final String LATEST_CACHE_KEY = "metrics:latest";

    public MetricsSnapshot create(MetricsSnapshot snapshot) {
        metricsSnapshotMapper.insert(snapshot);
        cacheManager.evict(LATEST_CACHE_KEY);
        return snapshot;
    }

    public MetricsSnapshot findById(String id) {
        MetricsSnapshot cached = cacheManager.get(CACHE_PREFIX + id, MetricsSnapshot.class);
        if (cached != null) {
            return cached;
        }

        MetricsSnapshot snapshot = metricsSnapshotMapper.selectById(id);
        if (snapshot != null) {
            cacheManager.set(CACHE_PREFIX + id, snapshot, 3600);
        }
        return snapshot;
    }

    public List<MetricsSnapshot> findByTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        QueryWrapper<MetricsSnapshot> wrapper = new QueryWrapper<>();
        wrapper.between("timestamp", startTime, endTime).orderByAsc("timestamp");
        return metricsSnapshotMapper.selectList(wrapper);
    }

    public List<MetricsSnapshot> findByMetricName(String metricName, LocalDateTime startTime, LocalDateTime endTime) {
        QueryWrapper<MetricsSnapshot> wrapper = new QueryWrapper<>();
        wrapper.between("timestamp", startTime, endTime)
                .orderByAsc("timestamp");
        return metricsSnapshotMapper.selectList(wrapper);
    }

    public MetricsSnapshot findLatest() {
        List<MetricsSnapshot> cached = cacheManager.get(LATEST_CACHE_KEY, List.class);
        if (cached != null && !cached.isEmpty()) {
            return cached.get(0);
        }

        QueryWrapper<MetricsSnapshot> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("timestamp").last("LIMIT 1");
        List<MetricsSnapshot> snapshots = metricsSnapshotMapper.selectList(wrapper);

        if (snapshots != null && !snapshots.isEmpty()) {
            cacheManager.set(LATEST_CACHE_KEY, snapshots, 30);
            return snapshots.get(0);
        }
        return null;
    }

    public List<MetricsSnapshot> findLatest(int limit) {
        QueryWrapper<MetricsSnapshot> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("timestamp").last("LIMIT " + limit);
        return metricsSnapshotMapper.selectList(wrapper);
    }
}
