package com.enterprise.risk.orchestration.action;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.orchestration.core.ActionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 记录日志动作
 * 输出结构化日志，包含告警详情、上下文和执行参数
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogAction implements Action {

    private static final String ACTION_ID = "log_event";
    private static final String ACTION_NAME = "记录日志动作";

    private final ObjectMapper objectMapper;

    @Override
    public String getActionId() {
        return ACTION_ID;
    }

    @Override
    public String getActionName() {
        return ACTION_NAME;
    }

    @Override
    public boolean execute(AlertEvent alertEvent, ActionContext context) {
        try {
            String logLevel = context.getParameterOrDefault("log_level", "INFO");
            String logCategory = context.getParameterOrDefault("log_category", "RISK_ACTION");

            Map<String, Object> structuredLog = buildStructuredLog(alertEvent, context, logCategory);
            String logMessage = objectMapper.writeValueAsString(structuredLog);

            switch (logLevel.toUpperCase()) {
                case "DEBUG" -> log.debug("{} | {}", logCategory, logMessage);
                case "WARN" -> log.warn("{} | {}", logCategory, logMessage);
                case "ERROR" -> log.error("{} | {}", logCategory, logMessage);
                case "TRACE" -> log.trace("{} | {}", logCategory, logMessage);
                default -> log.info("{} | {}", logCategory, logMessage);
            }

            context.saveResult(ACTION_ID, true);
            return true;
        } catch (Exception e) {
            log.error("[LogAction] 日志记录失败, alertId={}", alertEvent.getAlertId(), e);
            context.saveResult(ACTION_ID, false);
            return false;
        }
    }

    /**
     * 构建结构化日志内容
     */
    private Map<String, Object> buildStructuredLog(AlertEvent alertEvent, ActionContext context, String category) {
        Map<String, Object> logEntry = new HashMap<>();

        logEntry.put("log_category", category);
        logEntry.put("log_timestamp", Instant.now().toEpochMilli());
        logEntry.put("execution_id", context.getExecutionId());
        logEntry.put("action_index", context.getActionIndex());
        logEntry.put("total_actions", context.getTotalActions());

        Map<String, Object> alertInfo = new HashMap<>();
        alertInfo.put("alert_id", alertEvent.getAlertId());
        alertInfo.put("fingerprint", alertEvent.getFingerprint());
        alertInfo.put("rule_id", alertEvent.getRuleId());
        alertInfo.put("rule_name", alertEvent.getRuleName());
        alertInfo.put("severity", alertEvent.getSeverity() != null ? alertEvent.getSeverity().name() : null);
        alertInfo.put("entity_id", alertEvent.getEntityId());
        alertInfo.put("entity_type", alertEvent.getEntityType());
        alertInfo.put("business_line", alertEvent.getBusinessLine());
        alertInfo.put("description", alertEvent.getDescription());
        alertInfo.put("risk_score", alertEvent.getRiskScore());
        alertInfo.put("rule_hit_count", alertEvent.getRuleHitCount());
        alertInfo.put("event_count", alertEvent.getEventCount());
        alertInfo.put("status", alertEvent.getStatus() != null ? alertEvent.getStatus().name() : null);
        alertInfo.put("created_at", alertEvent.getCreatedAt());
        logEntry.put("alert", alertInfo);

        if (alertEvent.getMetadata() != null && !alertEvent.getMetadata().isEmpty()) {
            logEntry.put("metadata", alertEvent.getMetadata());
        }

        if (!context.getParameters().isEmpty()) {
            logEntry.put("action_parameters", context.getParameters());
        }

        if (!context.getPreviousResults().isEmpty()) {
            logEntry.put("previous_action_results", context.getPreviousResults());
        }

        return logEntry;
    }
}
