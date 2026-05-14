package com.datamigrate.expression;

import com.datamigrate.expression.RuleExpressionParser.ParsedRule;
import com.datamigrate.entity.MappingRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdvancedTransformService {

    private final ExpressionEngine expressionEngine;
    private final RuleExpressionParser ruleParser;

    public Map<String, Object> transformRecord(Map<String, Object> sourceRecord,
                                                 List<MappingRule> mappingRules) {
        List<ParsedRule> parsedRules = ruleParser.parseRules(mappingRules);
        return transformWithParsedRules(sourceRecord, parsedRules);
    }

    public Map<String, Object> transformWithParsedRules(Map<String, Object> sourceRecord,
                                                          List<ParsedRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return new LinkedHashMap<>(sourceRecord);
        }

        Map<String, Object> targetRecord = new LinkedHashMap<>();
        Map<String, Object> context = new HashMap<>(sourceRecord);

        for (ParsedRule rule : rules) {
            try {
                Object value = processRule(rule, sourceRecord, context);
                if (value != null || rule.isHasDefaultValue()) {
                    if (value == null) {
                        value = rule.getDefaultValue();
                    }
                    targetRecord.put(rule.getTargetField(), value);
                }
            } catch (Exception e) {
                log.error("字段转换失败: source={}, target={}", 
                    rule.getSourceField(), rule.getTargetField(), e);
                if (rule.isHasDefaultValue()) {
                    targetRecord.put(rule.getTargetField(), rule.getDefaultValue());
                }
            }
        }

        return targetRecord;
    }

    private Object processRule(ParsedRule rule, Map<String, Object> source, 
                                Map<String, Object> context) {
        if (rule.isHasCondition()) {
            boolean conditionMet = expressionEngine.evaluateCondition(rule.getCondition(), context);
            if (!conditionMet) {
                return null;
            }
        }

        Object value;

        if (rule.isHasExpression()) {
            value = expressionEngine.evaluate(rule.getExpression(), context, 
                ExpressionEngine.ExpressionType.AVIATOR);
        } else if (rule.getSourceField() != null) {
            value = source.get(rule.getSourceField());
        } else {
            value = null;
        }

        if (value == null && rule.isHasDefaultValue()) {
            return rule.getDefaultValue();
        }

        if (value != null) {
            for (String transformation : rule.getTransformations()) {
                value = applySimpleTransformation(value, transformation);
            }

            if (rule.isHasValueMapping()) {
                String strValue = String.valueOf(value);
                if (rule.getValueMappings().containsKey(strValue)) {
                    value = rule.getValueMappings().get(strValue);
                }
            }

            if (rule.isHasFormat() && value instanceof Date) {
                value = formatDate((Date) value, rule.getFormatPattern());
            }

            if (rule.isHasConcatenate()) {
                value = processConcatenate(rule.getConcatenateConfig(), context);
            }
        }

        return value;
    }

    private Object applySimpleTransformation(Object value, String transformation) {
        if (value == null) return null;

        String trans = transformation.toLowerCase().trim();
        String strValue = value.toString();

        switch (trans) {
            case "uppercase":
                return strValue.toUpperCase();
            case "lowercase":
                return strValue.toLowerCase();
            case "trim":
                return strValue.trim();
            case "reverse":
                return new StringBuilder(strValue).reverse().toString();
            case "to_string":
                return strValue;
            case "to_int":
                try {
                    return Integer.parseInt(strValue.trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            case "to_long":
                try {
                    return Long.parseLong(strValue.trim());
                } catch (NumberFormatException e) {
                    return 0L;
                }
            case "to_double":
                try {
                    return Double.parseDouble(strValue.trim());
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            case "trim_quotes":
                return trimQuotes(strValue);
            case "to_hash":
                return String.valueOf(strValue.hashCode());
            case "length":
                return strValue.length();
            default:
                return value;
        }
    }

    private String trimQuotes(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private String formatDate(Date date, String pattern) {
        if (date == null || pattern == null) {
            return null;
        }
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(pattern);
            return sdf.format(date);
        } catch (Exception e) {
            log.warn("日期格式化失败: pattern={}", pattern, e);
            return null;
        }
    }

    private String processConcatenate(Object config, Map<String, Object> context) {
        if (!(config instanceof Map)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> concatConfig = (Map<String, Object>) config;

        List<?> fields = (List<?>) concatConfig.get("fields");
        String separator = (String) concatConfig.getOrDefault("separator", "");

        if (fields == null || fields.isEmpty()) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        boolean first = true;

        for (Object field : fields) {
            if (!first) {
                result.append(separator);
            }
            first = false;

            if (field instanceof String) {
                Object value = context.get((String) field);
                if (value != null) {
                    result.append(value);
                }
            } else if (field instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> literal = (Map<String, Object>) field;
                if (literal.containsKey("literal")) {
                    result.append(literal.get("literal"));
                }
            }
        }

        return result.toString();
    }

    public List<String> getSupportedTransformations() {
        return Arrays.asList(
            "uppercase", "lowercase", "trim", "reverse",
            "to_string", "to_int", "to_long", "to_double",
            "trim_quotes", "to_hash", "length"
        );
    }
}
