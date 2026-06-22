package com.enterprise.gateway.logprocessor.aggregation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * 时间窗口 + 维度的复合键类。
 * 用于标识特定时间窗口内特定服务和日志级别的聚合数据。
 * 包含窗口开始时间、服务名称和日志级别三个维度。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WindowKey {

    private long windowStart;
    private String service;
    private String level;

    /**
     * 判断两个WindowKey是否相等。
     *
     * @param o 比较的对象
     * @return 如果windowStart、service、level都相等则返回true
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        WindowKey windowKey = (WindowKey) o;
        return windowStart == windowKey.windowStart
                && Objects.equals(service, windowKey.service)
                && Objects.equals(level, windowKey.level);
    }

    /**
     * 计算哈希码。
     *
     * @return 基于windowStart、service、level计算的哈希码
     */
    @Override
    public int hashCode() {
        return Objects.hash(windowStart, service, level);
    }

    /**
     * 返回键的字符串表示。
     *
     * @return 包含windowStart、service、level的字符串
     */
    @Override
    public String toString() {
        return "WindowKey{" +
                "windowStart=" + windowStart +
                ", service='" + service + '\'' +
                ", level='" + level + '\'' +
                '}';
    }
}
