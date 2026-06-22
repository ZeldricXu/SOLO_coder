package com.enterprise.gateway.logprocessor.aggregation;

import com.enterprise.gateway.logprocessor.model.LogEntry;

/**
 * 窗口存储接口，用于存储和管理时间窗口内的聚合状态。
 * 支持添加日志条目、获取指定窗口的聚合状态、驱逐过期窗口等操作。
 */
public interface WindowStore {

    /**
     * 将日志条目添加到对应的时间窗口中进行聚合。
     *
     * @param timestamp 日志条目的时间戳
     * @param entry     日志条目
     */
    void add(long timestamp, LogEntry entry);

    /**
     * 获取指定时间戳所在窗口的聚合状态。
     *
     * @param timestamp 时间戳
     * @return 对应窗口的聚合状态，如果窗口不存在则返回null
     */
    AggregationState getWindow(long timestamp);

    /**
     * 驱逐过期的窗口数据。
     *
     * @param now 当前时间戳，用于判断哪些窗口已过期
     */
    void evictExpired(long now);

    /**
     * 获取当前存储的窗口数量。
     *
     * @return 窗口数量
     */
    int size();
}
