package com.enterprise.gateway.logprocessor.aggregation;

import com.enterprise.gateway.logprocessor.model.LogEntry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 时间窗口聚合的可变状态类。
 * 存储计数、总和、最小值、最大值和平方和等统计指标，用于计算平均值和方差。
 * 所有修改方法都是线程安全的，使用synchronized关键字保证并发访问安全。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AggregationState {

    private long count;
    private double sum;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;
    private double sumOfSquares;

    /**
     * 将日志条目合并到当前聚合状态中。
     * 尝试从duration字段解析数值进行统计计算。
     *
     * @param entry 日志条目
     */
    public synchronized void merge(LogEntry entry) {
        count++;
        double value = parseDuration(entry.getDuration());
        sum += value;
        sumOfSquares += value * value;
        if (value < min) {
            min = value;
        }
        if (value > max) {
            max = value;
        }
    }

    /**
     * 重置聚合状态为初始值。
     */
    public synchronized void reset() {
        count = 0;
        sum = 0.0;
        min = Double.POSITIVE_INFINITY;
        max = Double.NEGATIVE_INFINITY;
        sumOfSquares = 0.0;
    }

    /**
     * 将另一个聚合状态合并到当前状态中。
     *
     * @param other 另一个聚合状态
     */
    public synchronized void combine(AggregationState other) {
        if (other == null || other.count == 0) {
            return;
        }
        this.count += other.count;
        this.sum += other.sum;
        this.sumOfSquares += other.sumOfSquares;
        if (other.min < this.min) {
            this.min = other.min;
        }
        if (other.max > this.max) {
            this.max = other.max;
        }
    }

    /**
     * 获取平均值。
     *
     * @return 平均值，如果没有数据则返回0
     */
    public synchronized double getAverage() {
        return count > 0 ? sum / count : 0.0;
    }

    /**
     * 获取方差。
     *
     * @return 方差，如果没有数据则返回0
     */
    public synchronized double getVariance() {
        if (count <= 1) {
            return 0.0;
        }
        double avg = sum / count;
        return (sumOfSquares / count) - (avg * avg);
    }

    /**
     * 获取标准差。
     *
     * @return 标准差，如果没有数据则返回0
     */
    public synchronized double getStandardDeviation() {
        return Math.sqrt(getVariance());
    }

    /**
     * 解析duration字符串为double值。
     *
     * @param duration 持续时间字符串
     * @return 解析后的数值，解析失败返回0
     */
    private double parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return 0.0;
        }
        try {
            String cleaned = duration.replaceAll("[^\\d.]", "");
            if (cleaned.isEmpty()) {
                return 0.0;
            }
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
