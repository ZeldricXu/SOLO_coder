package com.solocoder.platform.core.transformer.impl;

import com.solocoder.platform.common.exception.BusinessException;
import com.solocoder.platform.core.model.DataRecord;
import com.solocoder.platform.core.model.TransformRule;
import com.solocoder.platform.core.transformer.DataTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DataTransformerImpl implements DataTransformer {

    @Override
    public DataRecord transform(DataRecord record, TransformRule rule) {
        if (record == null || record.getFields() == null) return record;
        Map<String, Object> fields = new HashMap<>(record.getFields());

        switch (rule.getType()) {
            case RENAME -> {
                Object value = fields.remove(rule.getSourceField());
                if (value != null) fields.put(rule.getTargetField(), value);
            }
            case CAST -> {
                Object value = fields.get(rule.getSourceField());
                if (value != null) {
                    String targetType = (String) rule.getParameters().getOrDefault("targetType", "string");
                    fields.put(rule.getSourceField(), castValue(value, targetType));
                }
            }
            case FORMAT -> {
                Object value = fields.get(rule.getSourceField());
                if (value != null) {
                    String pattern = (String) rule.getParameters().getOrDefault("pattern", "%s");
                    fields.put(rule.getTargetField() != null ? rule.getTargetField() : rule.getSourceField(),
                            String.format(pattern, value));
                }
            }
            case TRIM -> {
                Object value = fields.get(rule.getSourceField());
                if (value instanceof String s) {
                    fields.put(rule.getSourceField(), s.trim());
                }
            }
            case DEFAULT -> {
                if (!fields.containsKey(rule.getSourceField()) || fields.get(rule.getSourceField()) == null) {
                    fields.put(rule.getSourceField(), rule.getParameters().get("defaultValue"));
                }
            }
            case MAP -> {
                Object value = fields.get(rule.getSourceField());
                if (value != null && rule.getParameters().containsKey("mappings")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mappings = (Map<String, Object>) rule.getParameters().get("mappings");
                    Object mapped = mappings.get(value.toString());
                    if (mapped != null) {
                        fields.put(rule.getTargetField() != null ? rule.getTargetField() : rule.getSourceField(), mapped);
                    }
                }
            }
            case COMPUTE -> {
                String expression = (String) rule.getParameters().get("expression");
                if (expression != null) {
                    fields.put(rule.getTargetField(), evaluateExpression(expression, fields));
                }
            }
            case FILTER -> {
                String fieldToCheck = rule.getSourceField();
                if (fields.containsKey(fieldToCheck)) {
                    Object val = fields.get(fieldToCheck);
                    Object filterValue = rule.getParameters().get("value");
                    if (Objects.equals(val, filterValue)) {
                        return null;
                    }
                }
            }
            default -> log.warn("Unknown transform type: {}", rule.getType());
        }

        record.setFields(fields);
        return record;
    }

    @Override
    public List<DataRecord> transformBatch(List<DataRecord> records, List<TransformRule> rules) {
        return records.stream()
                .map(record -> {
                    DataRecord current = record;
                    for (TransformRule rule : rules) {
                        if (current == null) break;
                        current = transform(current, rule);
                    }
                    return current;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private Object castValue(Object value, String targetType) {
        return switch (targetType.toLowerCase()) {
            case "string" -> value.toString();
            case "integer", "int" -> {
                try { yield Integer.parseInt(value.toString()); }
                catch (NumberFormatException e) { yield value; }
            }
            case "long" -> {
                try { yield Long.parseLong(value.toString()); }
                catch (NumberFormatException e) { yield value; }
            }
            case "double" -> {
                try { yield Double.parseDouble(value.toString()); }
                catch (NumberFormatException e) { yield value; }
            }
            case "boolean" -> Boolean.parseBoolean(value.toString());
            default -> value;
        };
    }

    private Object evaluateExpression(String expression, Map<String, Object> fields) {
        return expression;
    }
}
