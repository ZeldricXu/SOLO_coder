package com.datamigrate.expression;

import com.googlecode.aviator.AviatorEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.jexl3.*;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ExpressionEngine {

    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final JexlEngine jexlEngine = new JexlBuilder().create();
    private final Map<String, Expression> spelCache = new ConcurrentHashMap<>();
    private final Map<String, JexlExpression> jexlCache = new ConcurrentHashMap<>();
    private final Map<String, com.googlecode.aviator.Expression> aviatorCache = new ConcurrentHashMap<>();

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    public enum ExpressionType {
        SPEL,
        JEXL,
        AVIATOR,
        SIMPLE
    }

    public Object evaluate(String expression, Map<String, Object> context) {
        return evaluate(expression, context, ExpressionType.SIMPLE);
    }

    public Object evaluate(String expression, Map<String, Object> context, ExpressionType type) {
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }

        try {
            switch (type) {
                case SPEL:
                    return evaluateSpel(expression, context);
                case JEXL:
                    return evaluateJexl(expression, context);
                case AVIATOR:
                    return evaluateAviator(expression, context);
                case SIMPLE:
                default:
                    return evaluateSimple(expression, context);
            }
        } catch (Exception e) {
            log.error("表达式计算失败: expression={}, type={}", expression, type, e);
            return null;
        }
    }

    private Object evaluateSpel(String expression, Map<String, Object> context) {
        Expression exp = spelCache.computeIfAbsent(expression, key -> spelParser.parseExpression(key));
        StandardEvaluationContext spelContext = new StandardEvaluationContext();
        spelContext.setVariables(context);
        return exp.getValue(spelContext);
    }

    private Object evaluateJexl(String expression, Map<String, Object> context) {
        JexlExpression exp = jexlCache.computeIfAbsent(expression, key -> {
            try {
                return jexlEngine.createExpression(key);
            } catch (JexlException e) {
                throw new RuntimeException("JEXL表达式解析失败: " + key, e);
            }
        });
        JexlContext jexlContext = new MapContext(context);
        return exp.evaluate(jexlContext);
    }

    private Object evaluateAviator(String expression, Map<String, Object> context) {
        com.googlecode.aviator.Expression exp = aviatorCache.computeIfAbsent(expression, key -> 
            AviatorEvaluator.compile(key, true));
        return exp.execute(context);
    }

    private Object evaluateSimple(String expression, Map<String, Object> context) {
        if (expression == null || context == null) {
            return null;
        }

        String trimmed = expression.trim();
        
        if (trimmed.contains("+") || trimmed.contains("-") || 
            trimmed.contains("*") || trimmed.contains("/")) {
            return evaluateAviator(trimmed, context);
        }

        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String inner = trimmed.substring(2, trimmed.length() - 1);
            return context.get(inner);
        }

        if (PLACEHOLDER_PATTERN.matcher(trimmed).find()) {
            return replacePlaceholders(trimmed, context);
        }

        if (context.containsKey(trimmed)) {
            return context.get(trimmed);
        }

        return trimmed;
    }

    private String replacePlaceholders(String template, Map<String, Object> context) {
        if (template == null || context == null) {
            return template;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String placeholder = matcher.group(1);
            Object value = context.get(placeholder);
            String replacement = (value != null) ? value.toString() : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    public boolean evaluateCondition(String condition, Map<String, Object> context) {
        if (condition == null || condition.trim().isEmpty()) {
            return true;
        }

        try {
            Object result = evaluateAviator(condition, context);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            if (result != null) {
                return Boolean.parseBoolean(result.toString());
            }
        } catch (Exception e) {
            log.warn("条件表达式计算失败: condition={}", condition, e);
        }
        return false;
    }

    public String resolveExpression(String expression, Map<String, Object> sourceRecord) {
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }

        Object result = evaluate(expression, sourceRecord, ExpressionType.AVIATOR);
        return result != null ? result.toString() : null;
    }

    public Map<String, Object> transformRecord(Map<String, Object> sourceRecord,
                                                 List<com.datamigrate.entity.MappingRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return new LinkedHashMap<>(sourceRecord);
        }

        Map<String, Object> targetRecord = new LinkedHashMap<>();
        Map<String, Object> context = new HashMap<>(sourceRecord);

        for (com.datamigrate.entity.MappingRule rule : rules) {
            String sourceField = rule.getSourceField();
            String targetField = rule.getTargetField();
            String transformation = rule.getTransformation();

            if (isComplexExpression(sourceField)) {
                Object computedValue = evaluateExpressionField(sourceField, context);
                if (computedValue != null) {
                    targetRecord.put(targetField, computedValue);
                }
            } else {
                Object value = sourceRecord.get(sourceField);
                
                if (transformation != null && !transformation.trim().isEmpty()) {
                    value = applyTransformation(value, transformation, context);
                }
                
                targetRecord.put(targetField, value);
            }
        }

        return targetRecord;
    }

    private boolean isComplexExpression(String field) {
        if (field == null) return false;
        return field.contains("+") || field.contains("-") || field.contains("*") ||
               field.contains("/") || field.contains("${") ||
               field.contains("string.concat") || field.contains("string.format");
    }

    private Object evaluateExpressionField(String expression, Map<String, Object> context) {
        try {
            return evaluateAviator(expression, context);
        } catch (Exception e) {
            log.warn("字段表达式计算失败: expression={}", expression, e);
            return null;
        }
    }

    private Object applyTransformation(Object value, String transformation, Map<String, Object> context) {
        if (value == null) {
            return getDefaultValue(transformation);
        }

        String trans = transformation.trim();
        Map<String, Object> evalContext = new HashMap<>(context);
        evalContext.put("value", value);

        switch (trans.toLowerCase()) {
            case "uppercase":
                return value.toString().toUpperCase();
            case "lowercase":
                return value.toString().toLowerCase();
            case "trim":
                return value.toString().trim();
            case "to_string":
                return value.toString();
            case "to_int":
                return convertToInt(value);
            case "to_long":
                return convertToLong(value);
            case "to_double":
                return convertToDouble(value);
            case "to_boolean":
                return convertToBoolean(value);
            default:
                return evaluateAviator(transformation, evalContext);
        }
    }

    private Object getDefaultValue(String transformation) {
        String lower = transformation.toLowerCase();
        if (lower.contains("default=")) {
            int idx = lower.indexOf("default=");
            String defaultValue = transformation.substring(idx + 8).trim();
            return defaultValue.replace("\"", "").replace("'", "");
        }
        return null;
    }

    private Integer convertToInt(Object value) {
        try {
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            return Integer.parseInt(value.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Long convertToLong(Object value) {
        try {
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            return Long.parseLong(value.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Double convertToDouble(Object value) {
        try {
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            return Double.parseDouble(value.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean convertToBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String str = value.toString().toLowerCase().trim();
        return "true".equals(str) || "yes".equals(str) || "1".equals(str) || "y".equals(str);
    }

    public void clearCache() {
        spelCache.clear();
        jexlCache.clear();
        aviatorCache.clear();
    }

    public int getCacheSize() {
        return spelCache.size() + jexlCache.size() + aviatorCache.size();
    }
}
