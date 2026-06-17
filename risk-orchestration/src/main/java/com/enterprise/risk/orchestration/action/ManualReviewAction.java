package com.enterprise.risk.orchestration.action;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.orchestration.core.ActionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 人工确认动作
 * 创建工单系统工单，并通过邮件/短信通知相关审核人员
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManualReviewAction implements Action {

    private static final String ACTION_ID = "require_manual_review";
    private static final String ACTION_NAME = "人工确认动作";

    /**
     * 工单创建Topic
     */
    private static final String TICKET_CREATE_TOPIC = "risk.ticket.create";

    /**
     * 邮件通知Topic
     */
    private static final String EMAIL_NOTIFY_TOPIC = "risk.notification.email";

    /**
     * 短信通知Topic
     */
    private static final String SMS_NOTIFY_TOPIC = "risk.notification.sms";

    private final KafkaTemplate<String, Object> kafkaTemplate;

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
        String ticketId = "TK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        String priority = mapSeverityToPriority(alertEvent.getSeverity() != null ? alertEvent.getSeverity().name() : "MEDIUM");
        List<String> assigneeEmails = context.getParameter("assignee_emails");
        List<String> assigneePhones = context.getParameter("assignee_phones");
        String slaHours = context.getParameterOrDefault("sla_hours", "24");

        try {
            createTicket(ticketId, alertEvent, priority, slaHours);

            if (assigneeEmails != null && !assigneeEmails.isEmpty()) {
                sendEmailNotification(assigneeEmails, ticketId, alertEvent, priority, slaHours);
            }

            if (assigneePhones != null && !assigneePhones.isEmpty()) {
                sendSmsNotification(assigneePhones, ticketId, alertEvent, priority);
            }

            alertEvent.getMetadata().put("ticket_id", ticketId);
            log.info("[ManualReviewAction] 人工工单创建成功, ticketId={}, alertId={}", ticketId, alertEvent.getAlertId());
            context.saveResult(ACTION_ID, ticketId);
            return true;
        } catch (Exception e) {
            log.error("[ManualReviewAction] 人工工单创建失败, alertId={}", alertEvent.getAlertId(), e);
            context.saveResult(ACTION_ID, null);
            return false;
        }
    }

    /**
     * 创建工单
     */
    private void createTicket(String ticketId, AlertEvent alertEvent, String priority, String slaHours) {
        Map<String, Object> ticket = new HashMap<>();
        ticket.put("ticket_id", ticketId);
        ticket.put("title", "[风控告警] " + truncate(alertEvent.getDescription(), 100));
        ticket.put("description", buildTicketDescription(alertEvent));
        ticket.put("priority", priority);
        ticket.put("status", "PENDING");
        ticket.put("source", "RISK_SYSTEM");
        ticket.put("alert_id", alertEvent.getAlertId());
        ticket.put("rule_id", alertEvent.getRuleId());
        ticket.put("entity_id", alertEvent.getEntityId());
        ticket.put("entity_type", alertEvent.getEntityType());
        ticket.put("business_line", alertEvent.getBusinessLine());
        ticket.put("severity", alertEvent.getSeverity() != null ? alertEvent.getSeverity().name() : null);
        ticket.put("risk_score", alertEvent.getRiskScore());
        ticket.put("sla_hours", slaHours);
        ticket.put("created_at", System.currentTimeMillis());

        kafkaTemplate.send(TICKET_CREATE_TOPIC, ticketId, ticket);
    }

    /**
     * 发送邮件通知
     */
    private void sendEmailNotification(List<String> emails, String ticketId, AlertEvent alertEvent, String priority, String slaHours) {
        Map<String, Object> email = new HashMap<>();
        email.put("recipients", emails);
        email.put("subject", "[风控告警][" + priority + "] 待审核工单 " + ticketId);
        email.put("template", "risk_manual_review");
        Map<String, Object> params = new HashMap<>();
        params.put("ticket_id", ticketId);
        params.put("alert_id", alertEvent.getAlertId());
        params.put("rule_name", alertEvent.getRuleName());
        params.put("description", alertEvent.getDescription());
        params.put("entity_id", alertEvent.getEntityId());
        params.put("severity", alertEvent.getSeverity() != null ? alertEvent.getSeverity().name() : null);
        params.put("risk_score", alertEvent.getRiskScore());
        params.put("priority", priority);
        params.put("sla_hours", slaHours);
        email.put("params", params);

        kafkaTemplate.send(EMAIL_NOTIFY_TOPIC, ticketId, email);
    }

    /**
     * 发送短信通知
     */
    private void sendSmsNotification(List<String> phones, String ticketId, AlertEvent alertEvent, String priority) {
        Map<String, Object> sms = new HashMap<>();
        sms.put("recipients", phones);
        sms.put("template_code", "SMS_RISK_MANUAL_REVIEW");
        Map<String, String> params = new HashMap<>();
        params.put("ticket_id", ticketId);
        params.put("priority", priority);
        params.put("rule_name", truncate(alertEvent.getRuleName(), 20));
        sms.put("params", params);

        kafkaTemplate.send(SMS_NOTIFY_TOPIC, ticketId, sms);
    }

    /**
     * 映射告警级别到工单优先级
     */
    private String mapSeverityToPriority(String severity) {
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> "P0";
            case "HIGH" -> "P1";
            case "MEDIUM" -> "P2";
            case "LOW" -> "P3";
            default -> "P2";
        };
    }

    /**
     * 构建工单描述
     */
    private String buildTicketDescription(AlertEvent alertEvent) {
        StringBuilder sb = new StringBuilder();
        sb.append("告警ID: ").append(alertEvent.getAlertId()).append("\n");
        sb.append("规则名称: ").append(alertEvent.getRuleName()).append("\n");
        sb.append("实体ID: ").append(alertEvent.getEntityId()).append(" (").append(alertEvent.getEntityType()).append(")\n");
        sb.append("业务线: ").append(alertEvent.getBusinessLine()).append("\n");
        sb.append("风险分: ").append(alertEvent.getRiskScore()).append("\n");
        sb.append("触发次数: ").append(alertEvent.getRuleHitCount()).append("\n");
        sb.append("详情: ").append(alertEvent.getDescription()).append("\n");
        if (alertEvent.getMetadata() != null && !alertEvent.getMetadata().isEmpty()) {
            sb.append("元数据: ").append(alertEvent.getMetadata()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 字符串截断
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
