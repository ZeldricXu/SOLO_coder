package com.enterprise.risk.storage.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CounterService {

    private final RedissonClient redissonClient;

    private static final String TOTAL_PREFIX = "risk:counter:total:";
    private static final String WINDOW_PREFIX = "risk:counter:window:";
    private static final long DEFAULT_WINDOW_SECONDS = 300;

    /**
     * 计数器类型枚举
     */
    public enum CounterType {
        RULE_HIT("rule_hit", "规则命中数"),
        ALERT_GENERATED("alert_generated", "告警生成数"),
        EVENT_PROCESSED("event_processed", "事件处理数"),
        EVENT_REJECTED("event_rejected", "事件拒绝数"),
        ACTION_EXECUTED("action_executed", "动作执行数"),
        ACTION_FAILED("action_failed", "动作失败数"),
        MODEL_INFERENCE("model_inference", "模型推理数"),
        MODEL_ANOMALY("model_anomaly", "模型异常检测数");

        private final String code;
        private final String description;

        CounterType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() {
            return code;
        }
    }

    /**
     * 增加计数器
     *
     * @param type   计数器类型
     * @param key    业务键（如ruleId、businessLine等）
     * @param value  增加的值
     */
    public void increment(CounterType type, String key, long value) {
        String totalKey = buildTotalKey(type, key);
        RAtomicLong counter = redissonClient.getAtomicLong(totalKey);
        counter.addAndGet(value);

        String windowKey = buildWindowKey(type, key);
        RScoredSortedSet<String> window = redissonClient.getScoredSortedSet(windowKey);
        long now = Instant.now().getEpochSecond();
        String bucket = String.valueOf(now / DEFAULT_WINDOW_SECONDS * DEFAULT_WINDOW_SECONDS);
        window.addAndGetRank(now, bucket + ":" + value);
        window.expire(1, TimeUnit.HOURS);
    }

    /**
     * 自增1
     *
     * @param type 计数器类型
     * @param key  业务键
     */
    public void increment(CounterType type, String key) {
        increment(type, key, 1);
    }

    /**
     * 获取总计数
     *
     * @param type 计数器类型
     * @param key  业务键
     * @return 总计数
     */
    public long getTotalCount(CounterType type, String key) {
        String totalKey = buildTotalKey(type, key);
        RAtomicLong counter = redissonClient.getAtomicLong(totalKey);
        return counter.get();
    }

    /**
     * 获取滑动窗口内的计数
     *
     * @param type         计数器类型
     * @param key          业务键
     * @param windowSeconds 窗口大小（秒）
     * @return 窗口内计数
     */
    public long getWindowCount(CounterType type, String key, long windowSeconds) {
        String windowKey = buildWindowKey(type, key);
        RScoredSortedSet<String> window = redissonClient.getScoredSortedSet(windowKey);
        long now = Instant.now().getEpochSecond();
        long startTime = now - windowSeconds;

        long count = 0;
        for (String member : window.valueRange(startTime, true, now, true)) {
            String[] parts = member.split(":", 2);
            if (parts.length == 2) {
                try {
                    count += Long.parseLong(parts[1]);
                } catch (NumberFormatException e) {
                    log.warn("解析窗口计数器值失败: {}", member);
                }
            }
        }
        return count;
    }

    /**
     * 获取最近1分钟的计数
     *
     * @param type 计数器类型
     * @param key  业务键
     * @return 1分钟内计数
     */
    public long getLastMinuteCount(CounterType type, String key) {
        return getWindowCount(type, key, 60);
    }

    /**
     * 获取最近5分钟的计数
     *
     * @param type 计数器类型
     * @param key  业务键
     * @return 5分钟内计数
     */
    public long getLast5MinutesCount(CounterType type, String key) {
        return getWindowCount(type, key, 300);
    }

    /**
     * 获取最近1小时的计数
     *
     * @param type 计数器类型
     * @param key  业务键
     * @return 1小时内计数
     */
    public long getLastHourCount(CounterType type, String key) {
        return getWindowCount(type, key, 3600);
    }

    /**
     * 计算QPS（每秒请求数，基于最近1分钟）
     *
     * @param type 计数器类型
     * @param key  业务键
     * @return QPS
     */
    public double getQPS(CounterType type, String key) {
        return getLastMinuteCount(type, key) / 60.0;
    }

    /**
     * 重置计数器
     *
     * @param type 计数器类型
     * @param key  业务键
     */
    public void reset(CounterType type, String key) {
        String totalKey = buildTotalKey(type, key);
        String windowKey = buildWindowKey(type, key);
        redissonClient.getAtomicLong(totalKey).set(0);
        redissonClient.getScoredSortedSet(windowKey).delete();
    }

    /**
     * 获取并重置计数器
     *
     * @param type 计数器类型
     * @param key  业务键
     * @return 重置前的总计数
     */
    public long getAndReset(CounterType type, String key) {
        String totalKey = buildTotalKey(type, key);
        String windowKey = buildWindowKey(type, key);
        long value = redissonClient.getAtomicLong(totalKey).getAndSet(0);
        redissonClient.getScoredSortedSet(windowKey).delete();
        return value;
    }

    private String buildTotalKey(CounterType type, String key) {
        return TOTAL_PREFIX + type.getCode() + ":" + key;
    }

    private String buildWindowKey(CounterType type, String key) {
        return WINDOW_PREFIX + type.getCode() + ":" + key;
    }
}
