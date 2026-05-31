package com.datastandard.modules.quality.rule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class BasicQualityRule implements QualityRule {

    private Map<String, Object> lastViolation;

    @Override
    public boolean validate(Map<String, Object> data) {
        log.info("执行基本质量规则验证");

        if (data == null || data.isEmpty()) {
            lastViolation = createViolation("root", "EMPTY_DATA", "数据为空", "ERROR");
            return false;
        }

        boolean isValid = true;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!validateField(entry.getKey(), entry.getValue())) {
                isValid = false;
            }
        }

        return isValid;
    }

    private boolean validateField(String fieldName, Object value) {
        if (value == null) {
            lastViolation = createViolation(fieldName, "NULL_VALUE", "字段值为null", "ERROR");
            return false;
        }

        if (value instanceof String) {
            return validateString(fieldName, (String) value);
        }

        if (value instanceof Number) {
            return validateNumber(fieldName, (Number) value);
        }

        return true;
    }

    private boolean validateString(String fieldName, String str) {
        if (str.trim().isEmpty()) {
            lastViolation = createViolation(fieldName, "EMPTY_STRING", "字符串为空", "WARNING");
            return false;
        }
        if (str.length() > 1000) {
            lastViolation = createViolation(fieldName, "STRING_TOO_LONG", "字符串超过1000字符", "WARNING");
            return false;
        }
        return true;
    }

    private boolean validateNumber(String fieldName, Number num) {
        if (num.doubleValue() < 0) {
            lastViolation = createViolation(fieldName, "NEGATIVE_VALUE", "数值为负", "WARNING");
            return false;
        }
        return true;
    }

    private Map<String, Object> createViolation(String field, String type, String message, String severity) {
        Map<String, Object> violation = new HashMap<>();
        violation.put("field", field);
        violation.put("type", type);
        violation.put("message", message);
        violation.put("severity", severity);
        return violation;
    }

    @Override
    public Map<String, Object> getLastViolation() {
        return lastViolation;
    }

    @Override
    public Map<String, Object> generateSuggestions(Map<String, Object> violation) {
        Map<String, Object> suggestions = new HashMap<>();
        suggestions.put("violation", violation);
        suggestions.put("suggestion", "请检查数据质量");
        suggestions.put("priority", "MEDIUM");
        return suggestions;
    }

    @Override
    public String getRuleName() {
        return "BASIC";
    }

    public boolean checkWithRetry(Map<String, Object> data, int retryCount) {
        for (int i = 0; i < retryCount; i++) {
            if (validate(data)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
