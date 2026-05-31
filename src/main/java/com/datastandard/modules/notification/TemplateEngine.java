package com.datastandard.modules.notification;

import cn.hutool.core.util.StrUtil;
import com.datastandard.modules.notification.dto.TemplateDefinition;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class TemplateEngine {

    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern IF_PATTERN = Pattern.compile("<#if\\s+([^>]+)>(.*?)</#if>", Pattern.DOTALL);
    private static final Pattern LIST_PATTERN = Pattern.compile("<#list\\s+([^\\s]+)\\s+as\\s+([^>]+)>(.*?)</#list>", Pattern.DOTALL);

    private final Map<String, TemplateDefinition> templateCache = new ConcurrentHashMap<>();
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @PostConstruct
    public void init() {
        registerBuiltInTemplates();
    }

    private void registerBuiltInTemplates() {
        TemplateDefinition alertTemplate = TemplateDefinition.builder()
                .code("SYSTEM_ALERT")
                .name("系统告警通知")
                .type("alert")
                .subjectTemplate("【告警】\${level}: \${title}")
                .contentTemplate("## \${title}\n\n" +
                        "**级别**: \${level}\n" +
                        "**时间**: \${timestamp?datetime}\n" +
                        "**服务**: \${service}\n" +
                        "**描述**: \${message}\n\n" +
                        "<#if error??>\n" +
                        "**错误详情**:\n```\n\${error}\n```\n" +
                        "</#if>\n\n" +
                        "<#if traceId??>\n" +
                        "**追踪ID**: \${traceId}\n" +
                        "</#if>")
                .supportedChannels(Set.of("EMAIL", "DINGTALK", "FEISHU", "WEBHOOK"))
                .enabled(true)
                .createdAt(Instant.now())
                .build();
        registerTemplate(alertTemplate);

        TemplateDefinition reportTemplate = TemplateDefinition.builder()
                .code("DAILY_REPORT")
                .name("每日数据报告")
                .type("report")
                .subjectTemplate("【日报】\${date} 数据报告")
                .contentTemplate("# \${date} 数据报告\n\n" +
                        "## 概览\n" +
                        "- 总请求数: \${metrics.totalRequests}\n" +
                        "- 成功数: \${metrics.successCount}\n" +
                        "- 失败数: \${metrics.failedCount}\n" +
                        "- 成功率: \${metrics.successRate?percent}\n\n" +
                        "## 详细数据\n" +
                        "<#list metrics.items as item>\n" +
                        "- \${item.name}: \${item.value}\n" +
                        "</#list>")
                .supportedChannels(Set.of("EMAIL", "WEBHOOK"))
                .enabled(true)
                .createdAt(Instant.now())
                .build();
        registerTemplate(reportTemplate);
    }

    public void registerTemplate(TemplateDefinition template) {
        templateCache.put(template.getCode(), template);
        log.info("Registered template: {}", template.getCode());
    }

    public void unregisterTemplate(String code) {
        templateCache.remove(code);
        log.info("Unregistered template: {}", code);
    }

    public TemplateDefinition getTemplate(String code) {
        return templateCache.get(code);
    }

    public boolean hasTemplate(String code) {
        return templateCache.containsKey(code);
    }

    public String render(String template, Map<String, Object> context) {
        if (StrUtil.isBlank(template)) {
            return template;
        }

        Map<String, Object> enhancedContext = new HashMap<>(context);
        enhancedContext.putIfAbsent("now", Instant.now());

        String result = template;

        result = processListDirectives(result, enhancedContext);
        result = processIfDirectives(result, enhancedContext);
        result = processExpressions(result, enhancedContext);

        return result;
    }

    public String renderSubject(String templateCode, Map<String, Object> context) {
        TemplateDefinition template = getTemplate(templateCode);
        if (template == null) {
            return "";
        }
        return render(template.getSubjectTemplate(), context);
    }

    public String renderContent(String templateCode, Map<String, Object> context) {
        TemplateDefinition template = getTemplate(templateCode);
        if (template == null) {
            return "";
        }
        return render(template.getContentTemplate(), context);
    }

    private String processExpressions(String template, Map<String, Object> context) {
        Matcher matcher = EXPRESSION_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String value = evaluateExpression(expression, context);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private String processIfDirectives(String template, Map<String, Object> context) {
        Matcher matcher = IF_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String condition = matcher.group(1).trim();
            String body = matcher.group(2);

            boolean result = evaluateCondition(condition, context);
            String replacement = result ? body : "";

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        String result = sb.toString();
        if (result.contains("<#if")) {
            return processIfDirectives(result, context);
        }
        return result;
    }

    private String processListDirectives(String template, Map<String, Object> context) {
        Matcher matcher = LIST_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String listVar = matcher.group(1).trim();
            String itemVar = matcher.group(2).trim();
            String body = matcher.group(3);

            Object listObj = getValueFromContext(listVar, context);
            StringBuilder result = new StringBuilder();

            if (listObj instanceof Iterable) {
                int index = 0;
                for (Object item : (Iterable<?>) listObj) {
                    Map<String, Object> itemContext = new HashMap<>(context);
                    itemContext.put(itemVar, item);
                    itemContext.put(itemVar + "_index", index);
                    itemContext.put(itemVar + "_has_next", index < ((Collection<?>) listObj).size() - 1);
                    result.append(render(body, itemContext));
                    index++;
                }
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(result.toString()));
        }
        matcher.appendTail(sb);

        String result = sb.toString();
        if (result.contains("<#list")) {
            return processListDirectives(result, context);
        }
        return result;
    }

    private String evaluateExpression(String expression, Map<String, Object> context) {
        String[] parts = expression.split("\\?");
        String varName = parts[0].trim();
        String format = parts.length > 1 ? parts[1].trim() : null;

        Object value = getValueFromContext(varName, context);
        if (value == null) {
            return "";
        }

        return formatValue(value, format);
    }

    private Object getValueFromContext(String path, Map<String, Object> context) {
        String[] keys = path.split("\\.");
        Object current = context;

        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(key);
            } else if (current != null) {
                try {
                    current = current.getClass().getMethod("get" +
                            Character.toUpperCase(key.charAt(0)) + key.substring(1)).invoke(current);
                } catch (Exception e) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }

        return current;
    }

    private boolean evaluateCondition(String condition, Map<String, Object> context) {
        condition = condition.trim();

        if (condition.contains("??")) {
            String varName = condition.replace("??", "").trim();
            return getValueFromContext(varName, context) != null;
        }

        if (condition.contains("==")) {
            String[] parts = condition.split("==", 2);
            Object left = getValueFromContext(parts[0].trim(), context);
            Object right = parseLiteral(parts[1].trim());
            return Objects.equals(left, right);
        }

        if (condition.contains("!=")) {
            String[] parts = condition.split("!=", 2);
            Object left = getValueFromContext(parts[0].trim(), context);
            Object right = parseLiteral(parts[1].trim());
            return !Objects.equals(left, right);
        }

        Object value = getValueFromContext(condition, context);
        return value != null && !Boolean.FALSE.equals(value);
    }

    private Object parseLiteral(String literal) {
        literal = literal.trim();
        if (literal.startsWith("\"") && literal.endsWith("\"")) {
            return literal.substring(1, literal.length() - 1);
        }
        if ("true".equals(literal)) return true;
        if ("false".equals(literal)) return false;
        try {
            return Long.parseLong(literal);
        } catch (NumberFormatException e) {
            try {
                return Double.parseDouble(literal);
            } catch (NumberFormatException e2) {
                return literal;
            }
        }
    }

    private String formatValue(Object value, String format) {
        if (format == null) {
            return String.valueOf(value);
        }

        if ("datetime".equals(format)) {
            if (value instanceof Instant) {
                return dateFormatter.format((Instant) value);
            }
        }

        if ("percent".equals(format)) {
            if (value instanceof Number) {
                return String.format("%.2f%%", ((Number) value).doubleValue() * 100);
            }
        }

        if ("upper".equals(format)) {
            return String.valueOf(value).toUpperCase();
        }

        if ("lower".equals(format)) {
            return String.valueOf(value).toLowerCase();
        }

        if ("capitalize".equals(format)) {
            String str = String.valueOf(value);
            if (str.isEmpty()) return str;
            return Character.toUpperCase(str.charAt(0)) + str.substring(1);
        }

        return String.valueOf(value);
    }
}
