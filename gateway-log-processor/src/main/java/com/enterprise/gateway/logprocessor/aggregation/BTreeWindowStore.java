package com.enterprise.gateway.logprocessor.aggregation;

import com.enterprise.gateway.logprocessor.model.LogEntry;

import java.util.Map;
import java.util.TreeMap;

/**
 * 基于TreeMap的窗口存储实现，作为性能比较的基准。
 * 使用TreeMap<Long, AggregationState>存储时间窗口数据，支持有序遍历和快速查找。
 * 所有方法都是线程安全的，使用synchronized关键字保护共享状态。
 */
public class BTreeWindowStore implements WindowStore {

    private static final long DEFAULT_RETENTION_MS = 24 * 60 * 60 * 1000L;

    private final long windowSizeMs;
    private final long retentionMs;
    private final TreeMap<Long, AggregationState> windows;

    /**
     * 构造一个BTreeWindowStore实例。
     *
     * @param windowSizeMs 窗口大小（毫秒）
     */
    public BTreeWindowStore(long windowSizeMs) {
        this(windowSizeMs, DEFAULT_RETENTION_MS);
    }

    /**
     * 构造一个BTreeWindowStore实例，指定保留时间。
     *
     * @param windowSizeMs 窗口大小（毫秒）
     * @param retentionMs  数据保留时间（毫秒）
     */
    public BTreeWindowStore(long windowSizeMs, long retentionMs) {
        if (windowSizeMs <= 0) {
            throw new IllegalArgumentException("windowSizeMs must be positive");
        }
        if (retentionMs <= 0) {
            throw new IllegalArgumentException("retentionMs must be positive");
        }
        this.windowSizeMs = windowSizeMs;
        this.retentionMs = retentionMs;
        this.windows = new TreeMap<>();
    }

    /**
     * 将日志条目添加到对应的时间窗口中。
     *
     * @param timestamp 日志条目的时间戳
     * @param entry     日志条目
     */
    @Override
    public synchronized void add(long timestamp, LogEntry entry) {
        long windowStart = timestamp - (timestamp % windowSizeMs);
        AggregationState state = windows.get(windowStart);
        if (state == null) {
            state = new AggregationState();
            windows.put(windowStart, state);
        }
        state.merge(entry);
    }

    /**
     * 获取指定时间戳所在窗口的聚合状态。
     *
     * @param timestamp 时间戳
     * @return 对应窗口的聚合状态，如果窗口不存在则返回null
     */
    @Override
    public synchronized AggregationState getWindow(long timestamp) {
        long windowStart = timestamp - (timestamp % windowSizeMs);
        return windows.get(windowStart);
    }

    /**
     * 驱逐过期的窗口数据，移除超过保留时间的窗口。
     *
     * @param now 当前时间戳
     */
    @Override
    public synchronized void evictExpired(long now) {
        long cutoff = now - retentionMs;
        Map<Long, AggregationState> expired = windows.headMap(cutoff, false);
        expired.clear();
    }

    /**
     * 获取当前存储的窗口数量。
     *
     * @return 窗口数量
     */
    @Override
    public synchronized int size() {
        return windows.size();
    }

    /**
     * 获取窗口大小。
     *
     * @return 窗口大小（毫秒）
     */
    public long getWindowSizeMs() {
        return windowSizeMs;
    }

    /**
     * 获取数据保留时间。
     *
     * @return 保留时间（毫秒）
     */
    public long getRetentionMs() {
        return retentionMs;
    }

    /**
     * 获取最早的窗口开始时间。
     *
     * @return 最早的窗口开始时间，如果没有数据则返回-1
     */
    public synchronized long getEarliestWindowStart() {
        return windows.isEmpty() ? -1 : windows.firstKey();
    }

    /**
     * 获取最新的窗口开始时间。
     *
     * @return 最新的窗口开始时间，如果没有数据则返回-1
     */
    public synchronized long getLatestWindowStart() {
        return windows.isEmpty() ? -1 : windows.lastKey();
    }
}
