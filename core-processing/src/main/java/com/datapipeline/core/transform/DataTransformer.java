package com.datapipeline.core.transform;

import com.datapipeline.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class DataTransformer {

    public Object transform(Object input, List<TransformRule> rules) {
        if (input == null) {
            return null;
        }
        Object result = input;
        for (TransformRule rule : rules) {
            result = applyRule(result, rule);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object applyRule(Object input, TransformRule rule) {
        return switch (rule.getType()) {
            case RENAME -> applyRename(input, rule);
            case REMOVE -> applyRemove(input, rule);
            case ADD -> applyAdd(input, rule);
            case NORMALIZE -> applyNormalize(input, rule);
            case MAP -> applyMap(input, rule);
            case FILTER -> applyFilter(input, rule);
        };
    }

    @SuppressWarnings("unchecked")
    private Object applyRename(Object input, TransformRule rule) {
        if (!(input instanceof Map)) {
            return input;
        }
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) input);
        String from = rule.getParam("from", String.class, "");
        String to = rule.getParam("to", String.class, "");
        if (!from.isEmpty() && map.containsKey(from)) {
            map.put(to, map.remove(from));
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Object applyRemove(Object input, TransformRule rule) {
        if (!(input instanceof Map)) {
            return input;
        }
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) input);
        String field = rule.getParam("field", String.class, "");
        List<String> fields = rule.getParamList("fields", String.class);
        if (!field.isEmpty()) {
            map.remove(field);
        }
        for (String f : fields) {
            map.remove(f);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Object applyAdd(Object input, TransformRule rule) {
        Map<String, Object> map = input instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) input)
                : new LinkedHashMap<>();
        String field = rule.getParam("field", String.class, "");
        Object value = rule.getParams().get("value");
        if (!field.isEmpty()) {
            map.put(field, value);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Object applyNormalize(Object input, TransformRule rule) {
        if (!(input instanceof Map)) {
            return input;
        }
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) input);
        String field = rule.getParam("field", String.class, "");
        String format = rule.getParam("format", String.class, "lowercase");
        if (!field.isEmpty() && map.containsKey(field)) {
            Object value = map.get(field);
            if (value instanceof String str) {
                map.put(field, switch (format) {
                    case "lowercase" -> str.toLowerCase();
                    case "uppercase" -> str.toUpperCase();
                    case "trim" -> str.trim();
                    default -> str;
                });
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Object applyMap(Object input, TransformRule rule) {
        if (!(input instanceof Map)) {
            return input;
        }
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) input);
        String field = rule.getParam("field", String.class, "");
        Map<String, Object> mapping = rule.getParam("mapping", Map.class, Collections.emptyMap());
        if (!field.isEmpty() && map.containsKey(field)) {
            Object key = map.get(field);
            if (key != null && mapping.containsKey(key.toString())) {
                map.put(field, mapping.get(key.toString()));
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Object applyFilter(Object input, TransformRule rule) {
        if (!(input instanceof Collection)) {
            return input;
        }
        String field = rule.getParam("field", String.class, "");
        Object expectedValue = rule.getParams().get("value");
        if (field.isEmpty()) {
            return input;
        }
        Collection<Object> result = new ArrayList<>();
        for (Object item : (Collection<Object>) input) {
            if (item instanceof Map<?, ?> itemMap) {
                Object actualValue = itemMap.get(field);
                if (Objects.equals(actualValue, expectedValue)) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    public Map<String, Object> standardize(Map<String, Object> input, StandardizationSchema schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (FieldDefinition fieldDef : schema.getFields()) {
            Object value = resolveValue(input, fieldDef.getPath());
            if (value != null) {
                result.put(fieldDef.getName(), convertType(value, fieldDef.getType()));
            } else if (fieldDef.isRequired()) {
                log.warn("Required field missing: {}", fieldDef.getPath());
            }
        }
        return result;
    }

    private Object resolveValue(Map<String, Object> map, String path) {
        String[] parts = path.split("\\.");
        Object current = map;
        for (String part : parts) {
            if (current instanceof Map<?, ?> currentMap) {
                current = currentMap.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private Object convertType(Object value, FieldDefinition.FieldType type) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case STRING -> value.toString();
            case INTEGER -> value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(value.toString());
            case LONG -> value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
            case DOUBLE -> value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(value.toString());
            case BOOLEAN -> value instanceof Boolean ? value : Boolean.parseBoolean(value.toString());
            case OBJECT -> value instanceof Map ? value : JsonUtil.toMap(value);
            case ARRAY -> value;
        };
    }

}
