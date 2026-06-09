package com.datateam.loganalyzer.notification;

import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.AlertSeverity;
import com.datateam.loganalyzer.util.TimeUtils;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TemplateEngine {

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

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern IF_PATTERN = Pattern.compile("\\{\\{#if\\s+([^}]+)}}([\\s\\S]*?)\\{\\{/if}}");
    private static final Pattern UNLESS_PATTERN = Pattern.compile("\\{\\{#unless\\s+([^}]+)}}([\\s\\S]*?)\\{\\{/unless}}");
    private static final Pattern EACH_PATTERN = Pattern.compile("\\{\\{#each\\s+([^}]+)}}([\\s\\S]*?)\\{\\{/each}}");

    private final Map<String, Object> globalVariables;

    public TemplateEngine() {
        this.globalVariables = new HashMap<>();
        this.globalVariables.put("appName", "LogAnalyzer");
        this.globalVariables.put("generatedAt", TimeUtils.formatInstant(Instant.now()));
    }

    public void setGlobalVariable(String key, Object value) {
        globalVariables.put(key, value);
    }

    public String render(AlertEvent alert) {
        return render(alert, DEFAULT_TEMPLATE);
    }

    public String render(AlertEvent alert, String template) {
        if (alert == null) return "";
        if (template == null || template.isEmpty()) {
            template = DEFAULT_TEMPLATE;
        }

        Map<String, Object> context = buildContext(alert);

        String result = template;
        result = renderEachBlocks(result, context);
        result = renderIfBlocks(result, context);
        result = renderUnlessBlocks(result, context);
        result = renderVariables(result, context);

        return result;
    }

    public String render(Map<String, Object> context, String template) {
        if (context == null || template == null || template.isEmpty()) {
            return "";
        }

        String result = template;
        result = renderEachBlocks(result, context);
        result = renderIfBlocks(result, context);
        result = renderUnlessBlocks(result, context);
        result = renderVariables(result, context);

        return result;
    }

    private Map<String, Object> buildContext(AlertEvent alert) {
        Map<String, Object> context = new HashMap<>(globalVariables);

        context.put("ruleId", alert.getRuleId());
        context.put("ruleName", alert.getRuleName());
        context.put("severity", formatSeverity(alert.getSeverity()));
        context.put("severityLevel", alert.getSeverity() != null ? alert.getSeverity().name() : "UNKNOWN");
        context.put("description", alert.getDescription());
        context.put("triggeredAt", alert.getTriggeredAt() != null ?
            TimeUtils.formatInstant(alert.getTriggeredAt()) : "");
        context.put("recoveredAt", alert.getRecoveredAt() != null ?
            TimeUtils.formatInstant(alert.getRecoveredAt()) : "");
        context.put("duration", alert.getDurationMinutes());
        context.put("durationMinutes", alert.getDurationMinutes());
        context.put("escalationCount", alert.getEscalationCount());
        context.put("isActive", alert.isActive());
        context.put("isRecovered", !alert.isActive() && alert.getRecoveredAt() != null);
        context.put("isEscalated", alert.getEscalationCount() > 0);
        context.put("generatedAt", TimeUtils.formatInstant(Instant.now()));

        StringBuilder detailsSb = new StringBuilder();
        if (alert.getDetails() != null && !alert.getDetails().isEmpty()) {
            for (String detail : alert.getDetails()) {
                detailsSb.append("- ").append(detail).append("\n");
            }
        }
        context.put("details", detailsSb.toString());
        context.put("detailsList", alert.getDetails() != null ? alert.getDetails() : Collections.emptyList());

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
        context.put("affectedPoints", pointsSb.toString());
        context.put("affectedPointsList", alert.getAffectedPoints() != null ? alert.getAffectedPoints() : Collections.emptyList());

        return context;
    }

    private String renderVariables(String template, Map<String, Object> context) {
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String variableName = matcher.group(1).trim();
            Object value = getValue(context, variableName);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value.toString() : ""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String renderIfBlocks(String template, Map<String, Object> context) {
        Matcher matcher = IF_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String condition = matcher.group(1).trim();
            String content = matcher.group(2);
            boolean result = evaluateCondition(condition, context);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(result ? content : ""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String renderUnlessBlocks(String template, Map<String, Object> context) {
        Matcher matcher = UNLESS_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String condition = matcher.group(1).trim();
            String content = matcher.group(2);
            boolean result = evaluateCondition(condition, context);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(!result ? content : ""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String renderEachBlocks(String template, Map<String, Object> context) {
        Matcher matcher = EACH_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String listName = matcher.group(1).trim();
            String content = matcher.group(2);

            Object listObj = getValue(context, listName);
            StringBuilder result = new StringBuilder();

            if (listObj instanceof Iterable) {
                int index = 0;
                for (Object item : (Iterable<?>) listObj) {
                    Map<String, Object> itemContext = new HashMap<>(context);
                    itemContext.put("this", item);
                    itemContext.put("@index", index);
                    itemContext.put("@first", index == 0);
                    itemContext.put("@last", false);
                    result.append(renderVariables(content, itemContext));
                    index++;
                }
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(result.toString()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private boolean evaluateCondition(String condition, Map<String, Object> context) {
        condition = condition.trim();

        if (condition.contains("==")) {
            String[] parts = condition.split("==", 2);
            String left = parts[0].trim();
            String right = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
            Object leftValue = getValue(context, left);
            return leftValue != null && leftValue.toString().equals(right);
        }

        if (condition.contains("!=")) {
            String[] parts = condition.split("!=", 2);
            String left = parts[0].trim();
            String right = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
            Object leftValue = getValue(context, left);
            return leftValue != null && !leftValue.toString().equals(right);
        }

        if (condition.contains(">")) {
            String[] parts = condition.split(">", 2);
            double left = getNumericValue(context, parts[0].trim());
            double right = getNumericValue(context, parts[1].trim());
            return left > right;
        }

        if (condition.contains("<")) {
            String[] parts = condition.split("<", 2);
            double left = getNumericValue(context, parts[0].trim());
            double right = getNumericValue(context, parts[1].trim());
            return left < right;
        }

        Object value = getValue(context, condition);
        return isTruthy(value);
    }

    private Object getValue(Map<String, Object> context, String path) {
        if (path == null || path.isEmpty()) return null;

        String[] parts = path.split("\\.");
        Object current = context;

        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return null;
            }
            if (current == null) return null;
        }

        return current;
    }

    private double getNumericValue(Map<String, Object> context, String expression) {
        expression = expression.trim();
        try {
            return Double.parseDouble(expression);
        } catch (NumberFormatException e) {
            Object value = getValue(context, expression);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            return 0;
        }
    }

    private boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        if (value instanceof String) return !((String) value).isEmpty();
        if (value instanceof Collection) return !((Collection<?>) value).isEmpty();
        if (value instanceof Map) return !((Map<?, ?>) value).isEmpty();
        return true;
    }

    private String formatSeverity(AlertSeverity severity) {
        if (severity == null) return "UNKNOWN";
        return getSeverityEmoji(severity) + " " + severity.name();
    }

    private String getSeverityEmoji(AlertSeverity severity) {
        if (severity == null) return "⚪";
        switch (severity) {
            case CRITICAL: return "🔴";
            case ERROR: return "🟠";
            case WARNING: return "🟡";
            case INFO: return "🔵";
            default: return "⚪";
        }
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

    public String getDefaultTemplate() {
        return DEFAULT_TEMPLATE;
    }
}
