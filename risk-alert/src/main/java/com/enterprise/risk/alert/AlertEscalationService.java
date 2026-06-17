package com.enterprise.risk.alert;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.common.alert.AlertSeverity;
import com.enterprise.risk.common.rule.RuleDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class AlertEscalationService {

    @Value("${risk.alert.escalation.default-threshold:5}")
    private int defaultEscalationThreshold;

    private final Map<String, RuleDefinition> ruleDefinitionCache = new ConcurrentHashMap<>();

    private final Map<String, EscalationState> stateStore = new ConcurrentHashMap<>();

    public boolean checkAndEscalate(AlertEvent alert) {
        if (alert == null) {
            return false;
        }

        String fingerprint = alert.getFingerprint();
        EscalationState state = stateStore.computeIfAbsent(fingerprint, k ->
                new EscalationState(alert.getSeverity()));

        AlertSeverity currentSeverity = alert.getSeverity();
        if (state.currentSeverity != null
                && state.currentSeverity.isHigherThan(currentSeverity)) {
            alert.setSeverity(state.currentSeverity);
            currentSeverity = state.currentSeverity;
        }

        int threshold = resolveEscalationThreshold(alert.getRuleId());
        int hitCount = alert.getRuleHitCount() != null ? alert.getRuleHitCount() : 1;

        if (hitCount >= threshold) {
            AlertSeverity newSeverity = nextSeverity(currentSeverity);

            if (newSeverity != currentSeverity) {
                AlertSeverity oldSeverity = currentSeverity;
                alert.setSeverity(newSeverity);
                state.currentSeverity = newSeverity;
                state.lastEscalationTime = System.currentTimeMillis();
                state.escalationCount++;

                log.info("告警升级: fingerprint={}, {} -> {}, 命中次数={}, 阈值={}",
                        fingerprint, oldSeverity, newSeverity, hitCount, threshold);

                recordEscalationMetadata(alert, oldSeverity, newSeverity, hitCount);
                return true;
            }
        }

        state.hitCount = hitCount;
        return false;
    }

    public void resetEscalationState(String fingerprint) {
        EscalationState removed = stateStore.remove(fingerprint);
        if (removed != null) {
            log.debug("已重置告警升级状态: fingerprint={}, 升级次数={}",
                    fingerprint, removed.escalationCount);
        }
    }

    public EscalationState getEscalationState(String fingerprint) {
        EscalationState state = stateStore.get(fingerprint);
        if (state == null) {
            return null;
        }
        return new EscalationState(state);
    }

    public void registerRuleDefinition(RuleDefinition ruleDef) {
        if (ruleDef != null && ruleDef.getRuleId() != null) {
            ruleDefinitionCache.put(ruleDef.getRuleId(), ruleDef);
        }
    }

    public void unregisterRuleDefinition(String ruleId) {
        ruleDefinitionCache.remove(ruleId);
    }

    public void batchRegisterRuleDefinitions(Map<String, RuleDefinition> ruleMap) {
        if (!CollectionUtils.isEmpty(ruleMap)) {
            ruleDefinitionCache.putAll(ruleMap);
            log.info("批量注册规则配置: {} 条", ruleMap.size());
        }
    }

    private int resolveEscalationThreshold(String ruleId) {
        RuleDefinition ruleDef = ruleDefinitionCache.get(ruleId);
        if (ruleDef != null && ruleDef.getEscalationThreshold() != null
                && ruleDef.getEscalationThreshold() > 0) {
            return ruleDef.getEscalationThreshold();
        }
        return defaultEscalationThreshold;
    }

    private AlertSeverity nextSeverity(AlertSeverity current) {
        if (current == null) {
            return AlertSeverity.INFO;
        }
        AlertSeverity[] values = AlertSeverity.values();
        int currentIndex = current.ordinal();
        if (currentIndex < values.length - 1) {
            return values[currentIndex + 1];
        }
        return current;
    }

    private void recordEscalationMetadata(AlertEvent alert,
                                          AlertSeverity from,
                                          AlertSeverity to,
                                          int hitCount) {
        Map<String, Object> metadata = alert.getMetadata();
        if (metadata == null) {
            metadata = new ConcurrentHashMap<>();
            alert.setMetadata(metadata);
        }

        metadata.put("escalated", true);
        metadata.put("escalation_from", from.getCode());
        metadata.put("escalation_to", to.getCode());
        metadata.put("escalation_hit_count", hitCount);
        metadata.put("escalation_time", System.currentTimeMillis());

        @SuppressWarnings("unchecked")
        java.util.List<String> history = (java.util.List<String>) metadata
                .computeIfAbsent("escalation_history", k -> new java.util.ArrayList<>());
        history.add(String.format("%s->%s (hit=%d, ts=%d)",
                from.getCode(), to.getCode(), hitCount, System.currentTimeMillis()));
    }

    public int getDefaultEscalationThreshold() {
        return defaultEscalationThreshold;
    }

    public void setDefaultEscalationThreshold(int threshold) {
        this.defaultEscalationThreshold = threshold;
    }

    public int getActiveStateCount() {
        return stateStore.size();
    }

    public void clearAllStates() {
        int count = stateStore.size();
        stateStore.clear();
        log.info("已清空所有告警升级状态，共 {} 条", count);
    }

    public static class EscalationState {
        public AlertSeverity currentSeverity;
        public int hitCount;
        public int escalationCount;
        public long lastEscalationTime;

        public EscalationState() {
        }

        public EscalationState(AlertSeverity initialSeverity) {
            this.currentSeverity = initialSeverity;
            this.hitCount = 0;
            this.escalationCount = 0;
            this.lastEscalationTime = 0L;
        }

        public EscalationState(EscalationState other) {
            this.currentSeverity = other.currentSeverity;
            this.hitCount = other.hitCount;
            this.escalationCount = other.escalationCount;
            this.lastEscalationTime = other.lastEscalationTime;
        }
    }
}
