package com.enterprise.risk.storage.redis;

import com.enterprise.risk.common.rule.RuleDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class WindowStateService {

    private final RedissonClient redissonClient;

    private static final String WINDOW_KEY_PREFIX = "risk:window:";

    /**
     * 添加事件到滑动窗口
     *
     * @param ruleId     规则ID
     * @param groupKey   分组键（基于groupBy字段生成）
     * @param eventId    事件ID
     * @param value      聚合字段值
     * @param timestamp  事件时间戳
     * @param windowSizeMs 窗口大小（毫秒）
     */
    public void addEvent(String ruleId, String groupKey, String eventId,
                         Double value, long timestamp, long windowSizeMs) {
        String key = buildWindowKey(ruleId, groupKey);
        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);
        String member = eventId + ":" + (value != null ? value : "0");
        zset.add(timestamp, member);
        zset.expire(windowSizeMs * 2, TimeUnit.MILLISECONDS);
        cleanupExpired(key, timestamp - windowSizeMs);
    }

    /**
     * 执行窗口聚合计算
     *
     * @param ruleId          规则ID
     * @param groupKey        分组键
     * @param aggregationType 聚合类型
     * @param windowSizeMs    窗口大小（毫秒）
     * @return 聚合结果
     */
    public Double aggregate(String ruleId, String groupKey,
                            RuleDefinition.WindowConfig.AggregationType aggregationType,
                            long windowSizeMs) {
        String key = buildWindowKey(ruleId, groupKey);
        long now = Instant.now().toEpochMilli();
        long startTime = now - windowSizeMs;
        cleanupExpired(key, startTime);

        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);
        Collection<String> members = zset.valueRange(startTime, true, now, true);

        if (members == null || members.isEmpty()) {
            return 0.0;
        }

        List<Double> values = new ArrayList<>();
        Set<String> distinctValues = new HashSet<>();
        for (String member : members) {
            String[] parts = member.split(":", 2);
            if (parts.length == 2) {
                try {
                    Double val = Double.parseDouble(parts[1]);
                    values.add(val);
                    distinctValues.add(parts[1]);
                } catch (NumberFormatException e) {
                    log.warn("解析窗口值失败: {}", member);
                }
            }
        }

        return switch (aggregationType) {
            case SUM -> sum(values);
            case AVG -> avg(values);
            case COUNT -> (double) values.size();
            case MAX -> max(values);
            case MIN -> min(values);
            case DISTINCT_COUNT -> (double) distinctValues.size();
        };
    }

    /**
     * 获取窗口内所有元素数量
     *
     * @param ruleId       规则ID
     * @param groupKey     分组键
     * @param windowSizeMs 窗口大小
     * @return 元素数量
     */
    public long getWindowCount(String ruleId, String groupKey, long windowSizeMs) {
        String key = buildWindowKey(ruleId, groupKey);
        long now = Instant.now().toEpochMilli();
        long startTime = now - windowSizeMs;
        cleanupExpired(key, startTime);
        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);
        return zset.count(startTime, true, now, true);
    }

    /**
     * 清除过期的窗口数据
     *
     * @param key       键
     * @param startTime 窗口起始时间
     */
    private void cleanupExpired(String key, long startTime) {
        try {
            RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);
            zset.removeRangeByScore(0, true, startTime - 1, true);
        } catch (Exception e) {
            log.warn("清理窗口过期数据失败: {}", key, e);
        }
    }

    /**
     * 清除指定规则的窗口状态
     *
     * @param ruleId   规则ID
     * @param groupKey 分组键
     */
    public void clearWindow(String ruleId, String groupKey) {
        String key = buildWindowKey(ruleId, groupKey);
        redissonClient.getScoredSortedSet(key).delete();
    }

    /**
     * 构建窗口Redis键
     */
    private String buildWindowKey(String ruleId, String groupKey) {
        return WINDOW_KEY_PREFIX + ruleId + ":" + groupKey;
    }

    private Double sum(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }

    private Double avg(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return BigDecimal.valueOf(avg).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private Double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    }

    private Double min(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
    }
}
