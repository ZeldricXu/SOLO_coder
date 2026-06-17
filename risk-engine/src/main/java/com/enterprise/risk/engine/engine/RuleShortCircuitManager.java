package com.enterprise.risk.engine.engine;

import com.enterprise.risk.common.event.RiskEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 规则短路管理器
 * 按实体ID/IP维度的短路记忆：某实体触发高优短路后N秒内不再执行低优先级规则
 * 减少重复计算，提升高并发场景下的吞吐量
 */
@Component
public class RuleShortCircuitManager {

    private static final Logger log = LoggerFactory.getLogger(RuleShortCircuitManager.class);

    /**
     * 实体维度的短路记录缓存
     * key: "entity:" + entityId
     * value: 短路信息
     */
    private final Cache<String, ShortCircuitRecord> shortCircuitCache;

    /**
     * 配置的短路有效时间（秒）
     */
    private final int defaultTtlSeconds;

    /**
     * 触发短路的最小优先级阈值
     * 只有优先级 <= 此值的规则命中时才会启用短路
     */
    private final int priorityThreshold;

    /**
     * 单个实体在窗口期内的最大短路次数
     */
    private final int maxShortCircuitsPerWindow;

    /**
     * 统计计数器（key: 维度, value: 次数）
     */
    private final ConcurrentHashMap<String, int[]> windowCounters = new ConcurrentHashMap<>();

    public RuleShortCircuitManager(
            @Value("${risk.engine.shortcircuit.ttl-seconds:300}") int ttlSeconds,
            @Value("${risk.engine.shortcircuit.priority-threshold:50}") int priorityThreshold,
            @Value("${risk.engine.shortcircuit.max-per-window:10}") int maxPerWindow,
            @Value("${risk.engine.shortcircuit.max-entries:100000}") int maxEntries) {
        this.defaultTtlSeconds = ttlSeconds;
        this.priorityThreshold = priorityThreshold;
        this.maxShortCircuitsPerWindow = maxPerWindow;

        this.shortCircuitCache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .removalListener((key, value, cause) ->
                        log.debug("短路记录过期/移除: key={}, cause={}", key, cause))
                .build();

        log.info("规则短路管理器初始化完成: ttl={}s, priorityThreshold={}, maxEntries={}",
                ttlSeconds, priorityThreshold, maxEntries);
    }

    /**
     * 记录规则命中触发短路
     */
    public void recordShortCircuit(RiskEvent event, CompiledRule rule) {
        if (rule.getPriority() != null && rule.getPriority() > priorityThreshold) {
            return;
        }
        if (!isShortCircuitAllowed(rule.getRuleId())) {
            return;
        }

        long expireAt = System.currentTimeMillis() + (defaultTtlSeconds * 1000L);
        ShortCircuitRecord record = new ShortCircuitRecord(
                rule.getRuleId(),
                rule.getRuleName(),
                rule.getPriority(),
                System.currentTimeMillis(),
                expireAt
        );

        if (event.getEntityId() != null) {
            String key = "entity:" + event.getEntityId();
            shortCircuitCache.put(key, record);
            incrementCounter(key);
            log.debug("实体短路记录: entityId={}, ruleId={}, priority={}",
                    event.getEntityId(), rule.getRuleId(), rule.getPriority());
        }

        if (event.getIp() != null) {
            String key = "ip:" + event.getIp();
            shortCircuitCache.put(key, record);
            incrementCounter(key);
            log.debug("IP短路记录: ip={}, ruleId={}, priority={}",
                    event.getIp(), rule.getRuleId(), rule.getPriority());
        }
    }

    /**
     * 检查当前规则是否应该被短路跳过
     * true表示应该跳过执行
     */
    public boolean shouldSkip(RiskEvent event, CompiledRule rule) {
        if (rule.getPriority() == null) {
            return false;
        }

        ShortCircuitRecord entityRecord = event.getEntityId() != null
                ? shortCircuitCache.getIfPresent("entity:" + event.getEntityId())
                : null;
        if (shouldSkipByRecord(entityRecord, rule)) {
            return true;
        }

        ShortCircuitRecord ipRecord = event.getIp() != null
                ? shortCircuitCache.getIfPresent("ip:" + event.getIp())
                : null;
        return shouldSkipByRecord(ipRecord, rule);
    }

    /**
     * 根据短路记录判断是否应该跳过当前规则
     * 当前规则优先级低于（数值大于）已命中的短路规则则跳过
     */
    private boolean shouldSkipByRecord(ShortCircuitRecord record, CompiledRule rule) {
        if (record == null) {
            return false;
        }
        if (record.expireAt() <= System.currentTimeMillis()) {
            return false;
        }
        Integer scPriority = record.triggeredPriority();
        Integer rulePriority = rule.getPriority();
        if (scPriority == null || rulePriority == null) {
            return false;
        }
        return rulePriority > scPriority;
    }

    /**
     * 检查某规则是否允许触发短路（防止超频率）
     */
    private boolean isShortCircuitAllowed(String ruleId) {
        String key = "rule:" + ruleId;
        int[] count = windowCounters.computeIfAbsent(key, k -> new int[]{0});
        if (count[0] >= maxShortCircuitsPerWindow) {
            return false;
        }
        return true;
    }

    private void incrementCounter(String key) {
        int[] count = windowCounters.computeIfAbsent(key, k -> new int[]{0});
        count[0]++;
    }

    /**
     * 清理过期计数器（定时清理用）
     */
    public void cleanupExpiredCounters() {
        windowCounters.clear();
        log.debug("短路计数器已清理");
    }

    /**
     * 主动清除某实体的短路记录
     */
    public void clearEntityRecord(String entityId) {
        if (entityId != null) {
            shortCircuitCache.invalidate("entity:" + entityId);
        }
    }

    /**
     * 主动清除某IP的短路记录
     */
    public void clearIpRecord(String ip) {
        if (ip != null) {
            shortCircuitCache.invalidate("ip:" + ip);
        }
    }

    /**
     * 清除所有短路记录
     */
    public void clearAll() {
        shortCircuitCache.invalidateAll();
        windowCounters.clear();
        log.info("所有短路记录已清除");
    }

    /**
     * 获取短路记录数量
     */
    public long getRecordCount() {
        return shortCircuitCache.estimatedSize();
    }

    /**
     * 短路记录数据结构
     */
    public record ShortCircuitRecord(
            String triggeredRuleId,
            String triggeredRuleName,
            Integer triggeredPriority,
            long triggeredAt,
            long expireAt
    ) {}
}
