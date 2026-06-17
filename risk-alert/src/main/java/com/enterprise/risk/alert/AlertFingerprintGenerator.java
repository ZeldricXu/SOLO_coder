package com.enterprise.risk.alert;

import com.enterprise.risk.common.alert.AlertSeverity;
import com.enterprise.risk.common.rule.RuleEvaluationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@Component
public class AlertFingerprintGenerator {

    @Value("${risk.alert.fingerprint.fields:ruleId,entityId,entityType,businessLine,severity}")
    private List<String> defaultFingerprintFields;

    private static final String SEPARATOR = "|";

    private static final List<String> STATIC_FIELDS = Arrays.asList(
            "ruleId", "entityId", "entityType", "businessLine", "severity"
    );

    public String generate(RuleEvaluationResult result,
                           AlertSeverity severity,
                           Map<String, Object> dynamicFields) {
        return generate(result, severity, dynamicFields, defaultFingerprintFields);
    }

    public String generate(RuleEvaluationResult result,
                           AlertSeverity severity,
                           Map<String, Object> dynamicFields,
                           List<String> fingerprintFields) {
        if (result == null) {
            throw new IllegalArgumentException("RuleEvaluationResult不能为空");
        }

        List<String> fields = fingerprintFields != null && !fingerprintFields.isEmpty()
                ? fingerprintFields
                : defaultFingerprintFields;

        Map<String, Object> values = new TreeMap<>();

        for (String field : fields) {
            Object value = resolveFieldValue(field, result, severity, dynamicFields);
            values.put(field, value != null ? value.toString() : "");
        }

        String raw = buildRawString(values);
        String fingerprint = sha256(raw);

        if (log.isDebugEnabled()) {
            log.debug("生成指纹: ruleId={}, raw={}, hash={}",
                    result.getRuleId(), truncate(raw, 200), fingerprint);
        }

        return fingerprint;
    }

    private Object resolveFieldValue(String field,
                                     RuleEvaluationResult result,
                                     AlertSeverity severity,
                                     Map<String, Object> dynamicFields) {
        if (!StringUtils.hasText(field)) {
            return null;
        }

        String lowerField = field.trim();

        switch (lowerField) {
            case "ruleId":
            case "rule_id":
                return result.getRuleId();
            case "entityId":
            case "entity_id":
                return extractEntityId(result);
            case "entityType":
            case "entity_type":
                return extractEntityType(result);
            case "businessLine":
            case "business_line":
                return extractBusinessLine(result);
            case "severity":
                return severity != null ? severity.getCode() : null;
            case "ruleName":
            case "rule_name":
                return result.getRuleName();
            default:
                if (dynamicFields != null && dynamicFields.containsKey(field)) {
                    return dynamicFields.get(field);
                }
                if (result.getContext() != null && result.getContext().containsKey(field)) {
                    return result.getContext().get(field);
                }
                return extractFromMatchedEvents(result, field);
        }
    }

    private String extractEntityId(RuleEvaluationResult result) {
        if (!CollectionUtils.isEmpty(result.getMatchedEvents())) {
            return result.getMatchedEvents().get(0).getEntityId();
        }
        if (result.getContext() != null) {
            Object ctxEntityId = result.getContext().get("entityId");
            if (ctxEntityId != null) {
                return ctxEntityId.toString();
            }
            ctxEntityId = result.getContext().get("entity_id");
            if (ctxEntityId != null) {
                return ctxEntityId.toString();
            }
        }
        return "";
    }

    private String extractEntityType(RuleEvaluationResult result) {
        if (!CollectionUtils.isEmpty(result.getMatchedEvents())) {
            return result.getMatchedEvents().get(0).getEntityType();
        }
        if (result.getContext() != null) {
            Object ctxEntityType = result.getContext().get("entityType");
            if (ctxEntityType != null) {
                return ctxEntityType.toString();
            }
            ctxEntityType = result.getContext().get("entity_type");
            if (ctxEntityType != null) {
                return ctxEntityType.toString();
            }
        }
        return "";
    }

    private String extractBusinessLine(RuleEvaluationResult result) {
        if (!CollectionUtils.isEmpty(result.getMatchedEvents())) {
            return result.getMatchedEvents().get(0).getBusinessLine();
        }
        if (result.getContext() != null) {
            Object ctxBizLine = result.getContext().get("businessLine");
            if (ctxBizLine != null) {
                return ctxBizLine.toString();
            }
            ctxBizLine = result.getContext().get("business_line");
            if (ctxBizLine != null) {
                return ctxBizLine.toString();
            }
        }
        return "";
    }

    private Object extractFromMatchedEvents(RuleEvaluationResult result, String field) {
        if (CollectionUtils.isEmpty(result.getMatchedEvents())) {
            return null;
        }
        Map<String, Object> attrs = result.getMatchedEvents().get(0).getAttributes();
        if (attrs != null) {
            return attrs.get(field);
        }
        return null;
    }

    private String buildRawString(Map<String, Object> values) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) {
                sb.append(SEPARATOR);
            }
            sb.append(entry.getKey()).append("=");
            Object val = entry.getValue();
            sb.append(val != null ? val.toString() : "");
            first = false;
        }
        return sb.toString();
    }

    private String sha256(String input) {
        return DigestUtils.sha256Hex(
                input.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }

    public List<String> getDefaultFingerprintFields() {
        return new ArrayList<>(defaultFingerprintFields);
    }

    public void setDefaultFingerprintFields(List<String> fields) {
        this.defaultFingerprintFields = fields != null
                ? new ArrayList<>(fields)
                : Collections.emptyList();
    }

    public static List<String> getStaticFields() {
        return new ArrayList<>(STATIC_FIELDS);
    }
}
