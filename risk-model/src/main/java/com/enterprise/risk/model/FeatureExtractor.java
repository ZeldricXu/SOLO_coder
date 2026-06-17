package com.enterprise.risk.model;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.model.ModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class FeatureExtractor {

    private static final Pattern EXPRESSION_PATTERN = Pattern.compile(
            "\\$\\{(event|context|redis)\\.([a-zA-Z0-9_.\\[\\]]+)}"
    );

    private static final String DEFAULT_FLOAT_VALUE = "0.0f";

    public float[] extractFeatures(RiskEvent event,
                                   Map<String, Object> context,
                                   ModelConfig config) {
        List<String> featureNames = config.getFeatureNames();
        Map<String, String> extractors = config.getFeatureExtractors();
        Map<String, Object> defaultValues = config.getDefaultValues();

        if (featureNames == null || featureNames.isEmpty()) {
            log.warn("模型 [{}] 未配置特征名称列表，返回空特征向量", config.getModelId());
            return new float[0];
        }

        float[] features = new float[featureNames.size()];
        Map<String, Object> extractionContext = buildExtractionContext(event, context);

        for (int i = 0; i < featureNames.size(); i++) {
            String featureName = featureNames.get(i);
            try {
                features[i] = extractSingleFeature(
                        featureName,
                        extractors,
                        defaultValues,
                        extractionContext,
                        event,
                        context
                );
            } catch (Exception e) {
                log.warn("提取特征 [{}] 失败，使用默认值0.0，模型: {}",
                        featureName, config.getModelId(), e);
                features[i] = 0.0f;
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("特征提取完成: 模型={}, 维度={}", config.getModelId(), features.length);
        }

        return features;
    }

    private Map<String, Object> buildExtractionContext(RiskEvent event,
                                                       Map<String, Object> context) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("event", event);
        ctx.put("context", context != null ? context : new HashMap<>());
        return ctx;
    }

    private float extractSingleFeature(String featureName,
                                       Map<String, String> extractors,
                                       Map<String, Object> defaultValues,
                                       Map<String, Object> extractionContext,
                                       RiskEvent event,
                                       Map<String, Object> context) {
        Object value = null;

        if (extractors != null && extractors.containsKey(featureName)) {
            String expression = extractors.get(featureName);
            value = evaluateExpression(expression, event, context);
        }

        if (value == null && event != null && event.getAttributes() != null) {
            value = event.getAttributes().get(featureName);
        }

        if (value == null && context != null) {
            value = context.get(featureName);
        }

        if (value == null && defaultValues != null && defaultValues.containsKey(featureName)) {
            value = defaultValues.get(featureName);
            if (log.isDebugEnabled()) {
                log.debug("特征 [{}] 使用默认值: {}", featureName, value);
            }
        }

        return convertToFloat(value, featureName);
    }

    private Object evaluateExpression(String expression,
                                      RiskEvent event,
                                      Map<String, Object> context) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }

        if (!expression.contains("${")) {
            return resolveSimplePath(expression, event, context);
        }

        Matcher matcher = EXPRESSION_PATTERN.matcher(expression);
        if (matcher.find()) {
            String source = matcher.group(1);
            String path = matcher.group(2);

            switch (source) {
                case "event":
                    return resolveEventPath(path, event);
                case "context":
                    return resolveContextPath(path, context);
                case "redis":
                    log.debug("Redis特征提取需要外部Redis上下文，路径: {}", path);
                    return null;
                default:
                    log.warn("未知的表达式来源: {}", source);
                    return null;
            }
        }

        return expression;
    }

    private Object resolveSimplePath(String path, RiskEvent event, Map<String, Object> context) {
        if (path.startsWith("event.")) {
            return resolveEventPath(path.substring(6), event);
        }
        if (path.startsWith("context.")) {
            return resolveContextPath(path.substring(8), context);
        }

        Object value = null;
        if (event != null && event.getAttributes() != null) {
            value = event.getAttributes().get(path);
        }
        if (value == null && context != null) {
            value = context.get(path);
        }
        return value;
    }

    private Object resolveEventPath(String path, RiskEvent event) {
        if (event == null) {
            return null;
        }

        String[] parts = path.split("\\.");
        if (parts.length == 0) {
            return null;
        }

        String firstPart = parts[0];
        Object base = getEventField(event, firstPart);

        if (parts.length == 1) {
            return base;
        }

        return navigateNestedPath(base, parts, 1);
    }

    private Object getEventField(RiskEvent event, String fieldName) {
        switch (fieldName) {
            case "eventId":
            case "event_id":
                return event.getEventId();
            case "eventType":
            case "event_type":
                return event.getEventType();
            case "businessLine":
            case "business_line":
                return event.getBusinessLine();
            case "timestamp":
                return event.getTimestamp();
            case "entityId":
            case "entity_id":
                return event.getEntityId();
            case "entityType":
            case "entity_type":
                return event.getEntityType();
            case "source":
                return event.getSource();
            case "sessionId":
            case "session_id":
                return event.getSessionId();
            case "ip":
                return event.getIp();
            case "userId":
            case "user_id":
                return event.getUserId();
            case "attributes":
                return event.getAttributes();
            default:
                if (event.getAttributes() != null) {
                    return event.getAttributes().get(fieldName);
                }
                return null;
        }
    }

    private Object resolveContextPath(String path, Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        String[] parts = path.split("\\.");
        return navigateNestedPath(context, parts, 0);
    }

    @SuppressWarnings("unchecked")
    private Object navigateNestedPath(Object base, String[] parts, int startIndex) {
        Object current = base;
        for (int i = startIndex; i < parts.length; i++) {
            if (current == null) {
                return null;
            }

            String part = parts[i];
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                String arrayIndex = extractArrayIndex(part);
                if (arrayIndex != null) {
                    String listName = part.substring(0, part.indexOf('['));
                    if (current instanceof Map) {
                        Object listObj = ((Map<String, Object>) current).get(listName);
                        if (listObj instanceof List) {
                            int idx = Integer.parseInt(arrayIndex);
                            List<?> list = (List<?>) listObj;
                            if (idx >= 0 && idx < list.size()) {
                                current = list.get(idx);
                            } else {
                                return null;
                            }
                        } else {
                            return null;
                        }
                    }
                } else {
                    return null;
                }
            }
        }
        return current;
    }

    private String extractArrayIndex(String part) {
        int start = part.indexOf('[');
        int end = part.indexOf(']');
        if (start >= 0 && end > start) {
            return part.substring(start + 1, end);
        }
        return null;
    }

    private float convertToFloat(Object value, String featureName) {
        if (value == null) {
            return 0.0f;
        }

        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }

        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1.0f : 0.0f;
        }

        if (value instanceof String) {
            String str = ((String) value).trim();
            if (str.isEmpty()) {
                return 0.0f;
            }
            try {
                return Float.parseFloat(str);
            } catch (NumberFormatException e) {
                log.warn("特征 [{}] 字符串值无法转换为float: {}", featureName, str);
                return 0.0f;
            }
        }

        if (value instanceof Enum) {
            return ((Enum<?>) value).ordinal();
        }

        log.warn("特征 [{}] 不支持的类型: {}，使用0.0",
                featureName, value.getClass().getName());
        return 0.0f;
    }

    public float[][] extractBatchFeatures(List<RiskEvent> events,
                                          Map<String, Object> sharedContext,
                                          ModelConfig config) {
        if (events == null || events.isEmpty()) {
            return new float[0][];
        }

        float[][] batch = new float[events.size()][];
        for (int i = 0; i < events.size(); i++) {
            batch[i] = extractFeatures(events.get(i), sharedContext, config);
        }
        return batch;
    }
}
