package com.enterprise.gateway.logprocessor.aggregation;

import com.enterprise.gateway.logprocessor.model.LogEntry;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 流聚合管道，使用窗口存储对日志条目进行多维度聚合。
 * 支持按服务和日志级别两个维度进行分组聚合，每个维度组合维护独立的窗口存储。
 * 线程安全设计，支持并发处理日志条目。
 */
public class StreamAggregationPipeline {

    private final Map<String, WindowStore> dimensionStores;
    private final Supplier<WindowStore> storeFactory;
    private final long windowSizeMs;

    /**
     * 构造一个StreamAggregationPipeline实例。
     * 使用提供的WindowStore原型创建新的窗口存储实例。
     *
     * @param prototype 窗口存储原型，用于创建新实例
     * @throws IllegalArgumentException 如果prototype不支持复制
     */
    public StreamAggregationPipeline(WindowStore prototype) {
        this.dimensionStores = new ConcurrentHashMap<>();
        this.windowSizeMs = extractWindowSize(prototype);
        this.storeFactory = createFactory(prototype);
    }

    /**
     * 构造一个StreamAggregationPipeline实例，使用自定义工厂创建窗口存储。
     *
     * @param storeFactory 窗口存储工厂
     * @param windowSizeMs 窗口大小（毫秒）
     */
    public StreamAggregationPipeline(Supplier<WindowStore> storeFactory, long windowSizeMs) {
        if (storeFactory == null) {
            throw new IllegalArgumentException("storeFactory must not be null");
        }
        if (windowSizeMs <= 0) {
            throw new IllegalArgumentException("windowSizeMs must be positive");
        }
        this.dimensionStores = new ConcurrentHashMap<>();
        this.storeFactory = storeFactory;
        this.windowSizeMs = windowSizeMs;
    }

    /**
     * 处理单个日志条目，将其添加到对应维度的窗口存储中。
     *
     * @param entry 日志条目
     */
    public void process(LogEntry entry) {
        if (entry == null) {
            return;
        }

        String dimensionKey = createDimensionKey(entry.getService(), entry.getLevel());
        WindowStore store = dimensionStores.computeIfAbsent(dimensionKey, k -> storeFactory.get());
        store.add(entry.getTimestamp(), entry);
    }

    /**
     * 获取当前所有维度和所有窗口的聚合状态快照。
     * 返回的是数据的副本，对快照的修改不会影响原始数据。
     *
     * @return 从WindowKey到AggregationState的映射
     */
    public Map<WindowKey, AggregationState> getSnapshot() {
        Map<WindowKey, AggregationState> snapshot = new HashMap<>();

        for (Map.Entry<String, WindowStore> storeEntry : dimensionStores.entrySet()) {
            String dimensionKey = storeEntry.getKey();
            WindowStore store = storeEntry.getValue();
            String[] parts = parseDimensionKey(dimensionKey);
            String service = parts[0];
            String level = parts[1];

            for (Map.Entry<Long, AggregationState> windowEntry : getAllWindows(store).entrySet()) {
                long windowStart = windowEntry.getKey();
                AggregationState state = windowEntry.getValue();
                WindowKey key = new WindowKey(windowStart, service, level);
                AggregationState stateCopy = copyState(state);
                snapshot.put(key, stateCopy);
            }
        }

        return snapshot;
    }

    /**
     * 获取指定维度的聚合状态快照。
     *
     * @param service 服务名称
     * @param level   日志级别
     * @return 从窗口开始时间到AggregationState的映射
     */
    public Map<Long, AggregationState> getDimensionSnapshot(String service, String level) {
        String dimensionKey = createDimensionKey(service, level);
        WindowStore store = dimensionStores.get(dimensionKey);
        if (store == null) {
            return new HashMap<>();
        }

        Map<Long, AggregationState> snapshot = new HashMap<>();
        for (Map.Entry<Long, AggregationState> entry : getAllWindows(store).entrySet()) {
            snapshot.put(entry.getKey(), copyState(entry.getValue()));
        }
        return snapshot;
    }

    /**
     * 驱逐所有维度存储中过期的窗口数据。
     *
     * @param now 当前时间戳
     */
    public void evictExpired(long now) {
        for (WindowStore store : dimensionStores.values()) {
            store.evictExpired(now);
        }
    }

    /**
     * 获取当前管理的维度数量。
     *
     * @return 维度组合数量
     */
    public int getDimensionCount() {
        return dimensionStores.size();
    }

    /**
     * 获取所有维度的总窗口数量。
     *
     * @return 总窗口数量
     */
    public int getTotalWindowCount() {
        int total = 0;
        for (WindowStore store : dimensionStores.values()) {
            total += store.size();
        }
        return total;
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
     * 创建维度键。
     *
     * @param service 服务名称
     * @param level   日志级别
     * @return 维度键字符串
     */
    private String createDimensionKey(String service, String level) {
        return (service == null ? "" : service) + "|" + (level == null ? "" : level);
    }

    /**
     * 解析维度键。
     *
     * @param dimensionKey 维度键字符串
     * @return 包含service和level的数组
     */
    private String[] parseDimensionKey(String dimensionKey) {
        int index = dimensionKey.indexOf('|');
        if (index < 0) {
            return new String[]{dimensionKey, ""};
        }
        return new String[]{
                dimensionKey.substring(0, index),
                dimensionKey.substring(index + 1)
        };
    }

    /**
     * 从原型中提取窗口大小。
     *
     * @param prototype 窗口存储原型
     * @return 窗口大小（毫秒）
     */
    private long extractWindowSize(WindowStore prototype) {
        if (prototype instanceof BTreeWindowStore) {
            return ((BTreeWindowStore) prototype).getWindowSizeMs();
        } else if (prototype instanceof RingBufferWindowStore) {
            return ((RingBufferWindowStore) prototype).getWindowSizeMs();
        } else {
            throw new IllegalArgumentException(
                    "Unsupported WindowStore type: " + prototype.getClass().getName());
        }
    }

    /**
     * 根据原型创建窗口存储工厂。
     *
     * @param prototype 窗口存储原型
     * @return 窗口存储工厂
     */
    private Supplier<WindowStore> createFactory(WindowStore prototype) {
        if (prototype instanceof BTreeWindowStore) {
            BTreeWindowStore btree = (BTreeWindowStore) prototype;
            long windowSize = btree.getWindowSizeMs();
            long retention = btree.getRetentionMs();
            return () -> new BTreeWindowStore(windowSize, retention);
        } else if (prototype instanceof RingBufferWindowStore) {
            RingBufferWindowStore ring = (RingBufferWindowStore) prototype;
            long windowSize = ring.getWindowSizeMs();
            int maxWindows = ring.getMaxWindows();
            long retention = ring.getRetentionMs();
            return () -> new RingBufferWindowStore(windowSize, maxWindows, retention);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported WindowStore type: " + prototype.getClass().getName());
        }
    }

    /**
     * 获取窗口存储中所有窗口的数据。
     *
     * @param store 窗口存储
     * @return 从窗口开始时间到聚合状态的映射
     */
    private Map<Long, AggregationState> getAllWindows(WindowStore store) {
        Map<Long, AggregationState> windows = new HashMap<>();

        if (store instanceof BTreeWindowStore) {
            BTreeWindowStore btree = (BTreeWindowStore) store;
            long earliest = btree.getEarliestWindowStart();
            long latest = btree.getLatestWindowStart();
            if (earliest >= 0 && latest >= 0) {
                for (long t = earliest; t <= latest; t += windowSizeMs) {
                    AggregationState state = store.getWindow(t);
                    if (state != null && state.getCount() > 0) {
                        windows.put(t, state);
                    }
                }
            }
        } else if (store instanceof RingBufferWindowStore) {
            RingBufferWindowStore ring = (RingBufferWindowStore) store;
            long now = System.currentTimeMillis();
            long maxAge = ring.getRetentionMs();
            long earliest = now - maxAge;
            long latest = now;
            for (long t = earliest; t <= latest; t += windowSizeMs) {
                AggregationState state = store.getWindow(t);
                if (state != null && state.getCount() > 0) {
                    windows.put(t, state);
                }
            }
        }

        return windows;
    }

    /**
     * 创建聚合状态的副本。
     *
     * @param state 原始聚合状态
     * @return 聚合状态的副本
     */
    private AggregationState copyState(AggregationState state) {
        if (state == null) {
            return null;
        }
        synchronized (state) {
            return new AggregationState(
                    state.getCount(),
                    state.getSum(),
                    state.getMin(),
                    state.getMax(),
                    state.getSumOfSquares()
            );
        }
    }
}
