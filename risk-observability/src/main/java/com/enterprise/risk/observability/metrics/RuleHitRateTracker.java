package com.enterprise.risk.observability.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则命中率统计
 * 按规则/业务线/时间窗口统计命中率，数据存入Redis
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleHitRateTracker {

    private static final String RULE_EVAL_COUNT_PREFIX = "risk:stats:rule_eval:";
    private static final String RULE_HIT_COUNT_PREFIX = "risk:stats:rule_hit:";
    private static final String BUSINESS_EVAL_COUNT_PREFIX = "risk:stats:bl_eval:";
    private static final String BUSINESS_HIT_COUNT_PREFIX = "risk:stats:bl_hit:";
    private static final String RULE_RANK_KEY = "risk:stats:rule_hit_rank";

    private static final String WINDOW_5M = "5m";
    private static final String WINDOW_1H = "1h";
    private static final String WINDOW_1D = "1d";

    private final RedissonClient redissonClient;

    /**
     * 记录规则评估（未命中也算一次评估）
     */
    public void recordRuleEvaluation(String ruleId, String businessLine) {
        String timestampKey = getCurrentWindowKey();
        incrementCounter(RULE_EVAL_COUNT_PREFIX + WINDOW_5M + ":" + ruleId, timestampKey);
        incrementCounter(RULE_EVAL_COUNT_PREFIX + WINDOW_1H + ":" + ruleId, timestampKey);
        incrementCounter(RULE_EVAL_COUNT_PREFIX + WINDOW_1D + ":" + ruleId, timestampKey);

        if (businessLine != null) {
            incrementCounter(BUSINESS_EVAL_COUNT_PREFIX + WINDOW_5M + ":" + businessLine, timestampKey, 1);
            incrementCounter(BUSINESS_EVAL_COUNT_PREFIX + WINDOW_1H + ":" + businessLine, timestampKey, 1);
            incrementCounter(BUSINESS_EVAL_COUNT_PREFIX + WINDOW_1D + ":" + businessLine, timestampKey, 1);
        }
    }

    /**
     * 记录规则命中
     */
    public void recordRuleHit(String ruleId, String businessLine, double riskScore) {
        String timestampKey = getCurrentWindowKey();
        incrementCounter(RULE_HIT_COUNT_PREFIX + WINDOW_5M + ":" + ruleId, timestampKey);
        incrementCounter(RULE_HIT_COUNT_PREFIX + WINDOW_1H + ":" + ruleId, timestampKey);
        incrementCounter(RULE_HIT_COUNT_PREFIX + WINDOW_1D + ":" + ruleId, timestampKey);

        if (businessLine != null) {
            incrementCounter(BUSINESS_HIT_COUNT_PREFIX + WINDOW_5M + ":" + businessLine, timestampKey, 1);
            incrementCounter(BUSINESS_HIT_COUNT_PREFIX + WINDOW_1H + ":" + businessLine, timestampKey, 1);
            incrementCounter(BUSINESS_HIT_COUNT_PREFIX + WINDOW_1D + ":" + businessLine, timestampKey, 1);
        }

        RScoredSortedSet<String> rankSet = redissonClient.getScoredSortedSet(RULE_RANK_KEY + ":" + WINDOW_1D);
        rankSet.addScore(ruleId, 1.0);
    }

    /**
     * 获取指定规则的命中率
     */
    public RuleHitRate getRuleHitRate(String ruleId, String window) {
        long evalCount = getCount(RULE_EVAL_COUNT_PREFIX + window + ":" + ruleId);
        long hitCount = getCount(RULE_HIT_COUNT_PREFIX + window + ":" + ruleId);
        double hitRate = evalCount > 0 ? (double) hitCount / evalCount : 0.0;

        return RuleHitRate.builder()
                .ruleId(ruleId)
                .window(window)
                .evaluationCount(evalCount)
                .hitCount(hitCount)
                .hitRate(hitRate)
                .build();
    }

    /**
     * 获取指定业务线的命中率
     */
    public BusinessLineHitRate getBusinessLineHitRate(String businessLine, String window) {
        long evalCount = getCount(BUSINESS_EVAL_COUNT_PREFIX + window + ":" + businessLine);
        long hitCount = getCount(BUSINESS_HIT_COUNT_PREFIX + window + ":" + businessLine);
        double hitRate = evalCount > 0 ? (double) hitCount / evalCount : 0.0;

        return BusinessLineHitRate.builder()
                .businessLine(businessLine)
                .window(window)
                .evaluationCount(evalCount)
                .hitCount(hitCount)
                .hitRate(hitRate)
                .build();
    }

    /**
     * 获取规则命中率排行榜（TOP N）
     */
    public List<RuleHitRate> getRuleHitRateRanking(int topN, String window) {
        RScoredSortedSet<String> rankSet = redissonClient.getScoredSortedSet(RULE_RANK_KEY + ":" + window);
        var entries = rankSet.entrySetReversed(0, Math.max(0, topN - 1));

        List<RuleHitRate> result = new ArrayList<>();
        for (var entry : entries) {
            String ruleId = entry.getValue();
            RuleHitRate hitRate = getRuleHitRate(ruleId, window);
            hitRate.setRankScore(entry.getScore());
            result.add(hitRate);
        }
        return result;
    }

    /**
     * 获取所有规则的命中率统计
     */
    public Map<String, RuleHitRate> getAllRuleHitRates(String window, List<String> ruleIds) {
        Map<String, RuleHitRate> result = new HashMap<>();
        for (String ruleId : ruleIds) {
            result.put(ruleId, getRuleHitRate(ruleId, window));
        }
        return result;
    }

    /**
     * 定时清理过期统计数据（每小时执行一次）
     */
    @Scheduled(cron = "0 0 * * * *")
    public void cleanupExpiredData() {
        long now = System.currentTimeMillis();
        long fiveMinutesAgo = now - 5 * 60 * 1000;
        long oneHourAgo = now - 60 * 60 * 1000;
        long oneDayAgo = now - 24 * 60 * 60 * 1000;

        cleanupWindowData(RULE_EVAL_COUNT_PREFIX + WINDOW_5M, fiveMinutesAgo);
        cleanupWindowData(RULE_HIT_COUNT_PREFIX + WINDOW_5M, fiveMinutesAgo);
        cleanupWindowData(RULE_EVAL_COUNT_PREFIX + WINDOW_1H, oneHourAgo);
        cleanupWindowData(RULE_HIT_COUNT_PREFIX + WINDOW_1H, oneHourAgo);
        cleanupWindowData(RULE_EVAL_COUNT_PREFIX + WINDOW_1D, oneDayAgo);
        cleanupWindowData(RULE_HIT_COUNT_PREFIX + WINDOW_1D, oneDayAgo);

        RScoredSortedSet<String> dailyRank = redissonClient.getScoredSortedSet(RULE_RANK_KEY + ":" + WINDOW_1D);
        dailyRank.expire(java.util.concurrent.TimeUnit.DAYS.toSeconds(2),
                java.util.concurrent.TimeUnit.SECONDS);

        log.info("[RuleHitRateTracker] 过期统计数据清理完成");
    }

    private void incrementCounter(String key, String field) {
        incrementCounter(key, field, 1);
    }

    private void incrementCounter(String key, String field, long delta) {
        redissonClient.getMap(key).addAndGet(field, delta);
        redissonClient.getMap(key).expire(48, java.util.concurrent.TimeUnit.HOURS);
    }

    private long getCount(String key) {
        RSet<String> fields = redissonClient.getMap(key).keySet();
        long total = 0;
        for (String field : fields) {
            Object val = redissonClient.getMap(key).get(field);
            if (val instanceof Long) {
                total += (Long) val;
            } else if (val instanceof Integer) {
                total += (Integer) val;
            }
        }
        return total;
    }

    private void cleanupWindowData(String prefix, long expireThreshold) {
        RSet<String> keys = redissonClient.getKeys().getKeysByPattern(prefix + ":*");
        for (String key : keys) {
            var map = redissonClient.getMap(key);
            List<String> toDelete = new ArrayList<>();
            for (Object o : map.keySet()) {
                String field = (String) o;
                try {
                    long ts = Long.parseLong(field.split(":")[0]);
                    if (ts < expireThreshold) {
                        toDelete.add(field);
                    }
                } catch (Exception e) {
                    toDelete.add(field);
                }
            }
            if (!toDelete.isEmpty()) {
                map.fastRemove(toDelete.toArray());
            }
        }
    }

    private String getCurrentWindowKey() {
        long now = System.currentTimeMillis();
        return now + ":" + LocalDate.ofInstant(Instant.ofEpochMilli(now),
                ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleHitRate implements Serializable {
        private String ruleId;
        private String ruleName;
        private String window;
        private Long evaluationCount;
        private Long hitCount;
        private Double hitRate;
        private Double rankScore;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BusinessLineHitRate implements Serializable {
        private String businessLine;
        private String window;
        private Long evaluationCount;
        private Long hitCount;
        private Double hitRate;
    }
}
