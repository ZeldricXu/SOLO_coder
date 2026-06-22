package com.enterprise.gateway.logprocessor.aggregation;

import com.enterprise.gateway.logprocessor.model.LogEntry;

/**
 * 基于固定大小环形缓冲区的窗口存储优化实现。
 * 使用预分配的数组存储窗口数据，通过模运算快速定位窗口位置，
 * 避免了TreeMap的内存分配和树平衡开销，提供更高的性能。
 * 所有方法都是线程安全的，使用synchronized关键字保护共享状态。
 *
 * <p>关键设计：
 * <ul>
 *   <li>使用并行数组存储聚合状态和对应窗口开始时间</li>
 *   <li>通过 (windowStart / windowSizeMs) % maxWindows 计算索引</li>
 *   <li>每个槽位预初始化AggregationState，避免运行时分配</li>
 *   <li>通过检查windowStarts数组检测循环覆盖，自动重置过期数据</li>
 *   <li>evictExpired为No-op，数据过期通过循环覆盖自然处理</li>
 * </ul>
 * </p>
 */
public class RingBufferWindowStore implements WindowStore {

    private static final long DEFAULT_RETENTION_MS = 24 * 60 * 60 * 1000L;

    private final long windowSizeMs;
    private final int maxWindows;
    private final long retentionMs;
    private final AggregationState[] buffer;
    private final long[] windowStarts;

    /**
     * 构造一个RingBufferWindowStore实例。
     *
     * @param windowSizeMs 窗口大小（毫秒），构造后不可更改
     * @param maxWindows   最大窗口数量，决定环形缓冲区大小
     */
    public RingBufferWindowStore(long windowSizeMs, int maxWindows) {
        this(windowSizeMs, maxWindows, DEFAULT_RETENTION_MS);
    }

    /**
     * 构造一个RingBufferWindowStore实例，指定保留时间。
     *
     * @param windowSizeMs 窗口大小（毫秒），构造后不可更改
     * @param maxWindows   最大窗口数量，决定环形缓冲区大小
     * @param retentionMs  数据保留时间（毫秒）
     */
    public RingBufferWindowStore(long windowSizeMs, int maxWindows, long retentionMs) {
        if (windowSizeMs <= 0) {
            throw new IllegalArgumentException("windowSizeMs must be positive");
        }
        if (maxWindows <= 0) {
            throw new IllegalArgumentException("maxWindows must be positive");
        }
        if (retentionMs <= 0) {
            throw new IllegalArgumentException("retentionMs must be positive");
        }
        this.windowSizeMs = windowSizeMs;
        this.maxWindows = maxWindows;
        this.retentionMs = retentionMs;
        this.buffer = new AggregationState[maxWindows];
        this.windowStarts = new long[maxWindows];

        for (int i = 0; i < maxWindows; i++) {
            buffer[i] = new AggregationState();
        }
    }

    /**
     * 将日志条目添加到对应的时间窗口中。
     * 通过模运算快速定位环形缓冲区槽位，检测到循环覆盖时自动重置状态。
     *
     * @param timestamp 日志条目的时间戳
     * @param entry     日志条目
     */
    @Override
    public synchronized void add(long timestamp, LogEntry entry) {
        long windowStart = timestamp - (timestamp % windowSizeMs);
        int index = (int) ((windowStart / windowSizeMs) % maxWindows);

        if (windowStarts[index] != windowStart) {
            buffer[index].reset();
            windowStarts[index] = windowStart;
        }

        buffer[index].merge(entry);
    }

    /**
     * 获取指定时间戳所在窗口的聚合状态。
     *
     * @param timestamp 时间戳
     * @return 对应窗口的聚合状态，如果窗口不存在或已被覆盖则返回null
     */
    @Override
    public synchronized AggregationState getWindow(long timestamp) {
        long windowStart = timestamp - (timestamp % windowSizeMs);
        int index = (int) ((windowStart / windowSizeMs) % maxWindows);

        if (windowStarts[index] == windowStart) {
            return buffer[index];
        }
        return null;
    }

    /**
     * 驱逐过期的窗口数据。
     * 环形缓冲区通过循环覆盖自然处理数据过期，此方法为可选操作。
     * 可选实现：遍历并清除窗口开始时间早于保留时间的槽位。
     *
     * @param now 当前时间戳
     */
    @Override
    public synchronized void evictExpired(long now) {
        long cutoff = now - retentionMs;
        for (int i = 0; i < maxWindows; i++) {
            if (windowStarts[i] != 0 && windowStarts[i] < cutoff) {
                windowStarts[i] = 0;
                buffer[i].reset();
            }
        }
    }

    /**
     * 获取当前存储的有效窗口数量。
     * 统计windowStarts数组中非零元素的数量。
     *
     * @return 有效窗口数量
     */
    @Override
    public synchronized int size() {
        int count = 0;
        for (long windowStart : windowStarts) {
            if (windowStart != 0) {
                count++;
            }
        }
        return count;
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
     * 获取最大窗口数量。
     *
     * @return 最大窗口数量
     */
    public int getMaxWindows() {
        return maxWindows;
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
     * 获取环形缓冲区容量。
     *
     * @return 缓冲区容量，即maxWindows
     */
    public int capacity() {
        return maxWindows;
    }

    /**
     * 检查缓冲区是否已满。
     *
     * @return 如果所有槽位都已使用则返回true
     */
    public synchronized boolean isFull() {
        return size() == maxWindows;
    }
}
