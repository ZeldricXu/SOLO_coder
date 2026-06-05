package com.datateam.loganalyzer.notification;

import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.NotificationConfig;
import com.datateam.loganalyzer.util.TimeUtils;

import java.time.Instant;

public class AlertTemplateEngine {

    private static final String DEFAULT_TEMPLATE =
        "## 🚨 告警通知\n\n" +
        "**告警名称**: ${ruleName}\n\n" +
        "**告警级别**: ${severity}\n\n" +
        "**触发时间**: ${triggeredAt}\n\n" +
        "**持续时间**: ${duration}分钟\n\n" +
        "**告警描述**: ${description}\n\n" +
        "### 详细信息:\n" +
        "${details}\n\n" +
        "---\n" +
        "*由 LogAnalyzer 自动生成于 ${generatedAt}*";

    public String render(AlertEvent alert) {
        return render(alert, DEFAULT_TEMPLATE);
    }

    public String render(AlertEvent alert, String template) {
        if (alert == null) return "";
        if (template == null || template.isEmpty()) {
            template = DEFAULT_TEMPLATE;
        }

        String result = template;
        result = replace(result, "ruleId", alert.getRuleId());
        result = replace(result, "ruleName", alert.getRuleName());
        result = replace(result, "severity", alert.getSeverity() != null ?
            getSeverityEmoji(alert.getSeverity()) + " " + alert.getSeverity() : "UNKNOWN");
        result = replace(result, "description", alert.getDescription());
        result = replace(result, "triggeredAt", alert.getTriggeredAt() != null ?
            TimeUtils.formatInstant(alert.getTriggeredAt()) : "");
        result = replace(result, "recoveredAt", alert.getRecoveredAt() != null ?
            TimeUtils.formatInstant(alert.getRecoveredAt()) : "");
        result = replace(result, "duration", String.valueOf(alert.getDurationMinutes()));
        result = replace(result, "escalationCount", String.valueOf(alert.getEscalationCount()));
        result = replace(result, "isActive", String.valueOf(alert.isActive()));
        result = replace(result, "generatedAt", TimeUtils.formatInstant(Instant.now()));

        StringBuilder detailsSb = new StringBuilder();
        if (alert.getDetails() != null && !alert.getDetails().isEmpty()) {
            for (String detail : alert.getDetails()) {
                detailsSb.append("- ").append(detail).append("\n");
            }
        }
        result = replace(result, "details", detailsSb.toString());

        StringBuilder pointsSb = new StringBuilder();
        if (alert.getAffectedPoints() != null && !alert.getAffectedPoints().isEmpty()) {
            for (var point : alert.getAffectedPoints()) {
                pointsSb.append("**窗口**: ").append(TimeUtils.formatInstant(point.getWindowStart()))
                    .append(" -> ").append(TimeUtils.formatInstant(point.getWindowEnd())).append("\n")
                    .append("- 总日志数: ").append(point.getTotalCount()).append("\n")
                    .append("- 错误数: ").append(point.getErrorCount()).append("\n")
                    .append("- 错误率: ").append(String.format("%.2f/min", point.getRatePerMinute())).append("\n");
            }
        }
        result = replace(result, "affectedPoints", pointsSb.toString());

        return result;
    }

    public String renderPlainText(AlertEvent alert) {
        StringBuilder sb = new StringBuilder();
        sb.append("[ALERT] ").append(alert.getRuleName()).append("\n");
        sb.append("Severity: ").append(alert.getSeverity()).append("\n");
        sb.append("Time: ").append(TimeUtils.formatInstant(alert.getTriggeredAt())).append("\n");
        sb.append("Duration: ").append(alert.getDurationMinutes()).append(" minutes\n");
        sb.append("Description: ").append(alert.getDescription()).append("\n");
        if (alert.getDetails() != null) {
            for (String detail : alert.getDetails()) {
                sb.append("  - ").append(detail).append("\n");
            }
        }
        return sb.toString();
    }

    public String renderSubject(AlertEvent alert) {
        return String.format("[%s] %s - %s",
            alert.getSeverity(),
            alert.getRuleName(),
            alert.getDescription() != null && alert.getDescription().length() > 50 ?
                alert.getDescription().substring(0, 50) + "..." : alert.getDescription());
    }

    private String replace(String template, String key, String value) {
        if (value == null) value = "";
        return template.replace("${" + key + "}", value);
    }

    private String getSeverityEmoji(Enum<?> severity) {
        switch (severity.name()) {
            case "CRITICAL": return "🔴";
            case "ERROR": return "🟠";
            case "WARNING": return "🟡";
            case "INFO": return "🔵";
            default: return "⚪";
        }
    }

    public String getWeChatWorkMarkdown(AlertEvent alert) {
        return render(alert);
    }

    public String getSlackMarkdown(AlertEvent alert) {
        String md = render(alert);
        md = md.replaceAll("## ", "*");
        md = md.replaceAll("### ", "*");
        md = md.replaceAll("\\*\\*", "*");
        return md;
    }
}
