package com.enterprise.risk.alert;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.common.alert.AlertSeverity;
import com.enterprise.risk.common.rule.RuleDefinition;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AlertSuppressionService {

    @Value("${risk.alert.suppression.ttl-seconds:1800}")
    private int defaultSuppressionTtlSeconds;

    @Value("${risk.alert.suppression.min-severity-diff:1}")
    private int minSeverityDiff;

    private final Cache<String, SuppressionEntry> suppressionCache;

    private final Map<String, RuleDefinition> ruleDefinitionCache = new ConcurrentHashMap<>();

    public AlertSuppressionService() {
        this.suppressionCache = CacheBuilder.newBuilder()
                .maximumSize(50000)
                .expireAfterWrite(1800, TimeUnit.SECONDS)
                .removalListener(notification -> {
                    if (log.isDebugEnabled()) {
                        log.debug("抑制缓存条目已过期: {}", notification.getKey());
                    }
                })
                .build();
    }

    public boolean checkSuppressed(AlertEvent alert) {
        if (alert == null) {
            return false;
        }

        String ruleId = alert.getRuleId();
        AlertSeverity severity = alert.getSeverity();
        String businessLine = alert.getBusinessLine();
        String entityId = alert.getEntityId();

        List<String> checkKeys = buildSuppressionCheckKeys(ruleId, businessLine, entityId);

        for (String key : checkKeys) {
            SuppressionEntry entry = suppressionCache.getIfPresent(key);
            if (entry != null && !isExpired(entry)) {
                if (shouldSuppress(entry, severity)) {
                    alert.setSuppressedBy(entry.suppressedByRuleId);
                    if (alert.getMetadata() == null) {
                        alert.setMetadata(new ConcurrentHashMap<>());
                    }
                    alert.getMetadata().put("suppressed_by_fingerprint", entry.suppressedByFingerprint);
                    alert.getMetadata().put("suppressed_reason", entry.reason);

                    if (log.isDebugEnabled()) {
                        log.debug("告警被抑制: ruleId={}, 被规则={}, key={}",
                                ruleId, entry.suppressedByRuleId, key);
                    }
                    return true;
                }
            }
        }

        return false;
    }

    public void recordSuppression(AlertEvent suppressorAlert) {
        if (suppressorAlert == null) {
            return;
        }

        RuleDefinition ruleDef = ruleDefinitionCache.get(suppressorAlert.getRuleId());
        List<String> suppressionRuleIds = ruleDef != null
                ? ruleDef.getSuppressionRuleIds()
                : null;

        if (CollectionUtils.isEmpty(suppressionRuleIds)) {
            return;
        }

        String businessLine = suppressorAlert.getBusinessLine();
        String entityId = suppressorAlert.getEntityId();

        SuppressionEntry entry = new SuppressionEntry();
        entry.suppressedByRuleId = suppressorAlert.getRuleId();
        entry.suppressedByFingerprint = suppressorAlert.getFingerprint();
        entry.suppressorSeverity = suppressorAlert.getSeverity();
        entry.suppressedRuleIds = new ArrayList<>(suppressionRuleIds);
        entry.createdAt = System.currentTimeMillis();
        entry.ttlSeconds = defaultSuppressionTtlSeconds;
        entry.reason = String.format("被高优先级规则[%s]抑制", suppressorAlert.getRuleName());

        for (String suppressedRuleId : suppressionRuleIds) {
            List<String> keys = buildSuppressionRecordKeys(
                    suppressedRuleId, businessLine, entityId);
            for (String key : keys) {
                suppressionCache.put(key, entry);
            }
        }

        if (log.isInfoEnabled()) {
            log.info("记录抑制关系: 抑制规则={}, 抑制规则数={}, 告警={}",
                    suppressorAlert.getRuleId(),
                    suppressionRuleIds.size(),
                    suppressorAlert.getAlertId());
        }
    }

    public void registerRuleDefinition(RuleDefinition ruleDef) {
        if (ruleDef != null && ruleDef.getRuleId() != null) {
            ruleDefinitionCache.put(ruleDef.getRuleId(), ruleDef);
        }
    }

    public void batchRegisterRuleDefinitions(Map<String, RuleDefinition> ruleMap) {
        if (!CollectionUtils.isEmpty(ruleMap)) {
            ruleDefinitionCache.putAll(ruleMap);
            log.info("批量注册抑制规则配置: {} 条", ruleMap.size());
        }
    }

    private List<String> buildSuppressionCheckKeys(String ruleId,
                                                   String businessLine,
                                                   String entityId) {
        List<String> keys = new ArrayList<>();

        if (ruleId != null) {
            if (entityId != null && businessLine != null) {
                keys.add("suppress:" + ruleId + ":" + businessLine + ":" + entityId);
            }
            if (businessLine != null) {
                keys.add("suppress:" + ruleId + ":" + businessLine);
            }
            keys.add("suppress:" + ruleId);
        }

        return keys;
    }

    private List<String> buildSuppressionRecordKeys(String suppressedRuleId,
                                                    String businessLine,
                                                    String entityId) {
        List<String> keys = new ArrayList<>();

        if (entityId != null && businessLine != null) {
            keys.add("suppress:" + suppressedRuleId + ":" + businessLine + ":" + entityId);
        }
        if (businessLine != null) {
            keys.add("suppress:" + suppressedRuleId + ":" + businessLine);
        }
        keys.add("suppress:" + suppressedRuleId);

        return keys;
    }

    private boolean shouldSuppress(SuppressionEntry entry, AlertSeverity targetSeverity) {
        if (entry == null || entry.suppressorSeverity == null || targetSeverity == null) {
            return false;
        }

        int levelDiff = entry.suppressorSeverity.getLevel() - targetSeverity.getLevel();
        return levelDiff >= minSeverityDiff;
    }

    private boolean isExpired(SuppressionEntry entry) {
        long elapsed = System.currentTimeMillis() - entry.createdAt;
        return elapsed > (long) entry.ttlSeconds * 1000L;
    }

    public void clearSuppression(String ruleId) {
        if (ruleId == null) {
            return;
        }
        suppressionCache.asMap().keySet().removeIf(k -> k.startsWith("suppress:" + ruleId));
        log.debug("已清除规则相关的抑制缓存: {}", ruleId);
    }

    public void clearAll() {
        long size = suppressionCache.size();
        suppressionCache.invalidateAll();
        log.info("已清空所有抑制缓存，共清除 {} 条", size);
    }

    public long getActiveSuppressionCount() {
        return suppressionCache.size();
    }

    public int getDefaultSuppressionTtlSeconds() {
        return defaultSuppressionTtlSeconds;
    }

    public void setDefaultSuppressionTtlSeconds(int seconds) {
        this.defaultSuppressionTtlSeconds = seconds;
    }

    public static class SuppressionEntry {
        String suppressedByRuleId;
        String suppressedByFingerprint;
        AlertSeverity suppressorSeverity;
        List<String> suppressedRuleIds;
        long createdAt;
        int ttlSeconds;
        String reason;
    }
}
