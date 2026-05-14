package com.configcenter.validation.rule;

import com.configcenter.common.exception.ConfigValidationException;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class EnumRule implements ValidationRule {
    
    @Builder.Default
    private String ruleId = "RULE_ENUM";
    
    @Builder.Default
    private String ruleType = "ENUM";
    
    @Builder.Default
    private String name = "枚举值校验";
    
    @Builder.Default
    private String description = "校验配置值是否在允许的枚举列表中";
    
    @Builder.Default
    private boolean enabled = true;
    
    @Builder.Default
    private int priority = 350;
    
    @Builder.Default
    private Map<String, Object> params = new HashMap<>();
    
    private String errorMessage;
    
    @SuppressWarnings("unchecked")
    @Override
    public void validate(String value, Map<String, Object> context) throws ConfigValidationException {
        if (value == null) {
            throw new ConfigValidationException("配置值不能为空");
        }
        
        List<String> allowedValues = new ArrayList<>();
        
        if (params.containsKey("values")) {
            Object valuesObj = params.get("values");
            if (valuesObj instanceof List) {
                for (Object item : (List<Object>) valuesObj) {
                    allowedValues.add(String.valueOf(item));
                }
            }
        }
        
        if (allowedValues.isEmpty()) {
            logWarn("Enum rule has no allowed values, validation skipped");
            return;
        }
        
        boolean caseSensitive = !params.containsKey("caseInsensitive") || 
                !Boolean.parseBoolean(String.valueOf(params.get("caseInsensitive")));
        
        boolean found = false;
        for (String allowed : allowedValues) {
            if (caseSensitive ? allowed.equals(value) : allowed.equalsIgnoreCase(value)) {
                found = true;
                break;
            }
        }
        
        if (!found) {
            String msg = errorMessage != null ? errorMessage : 
                    String.format("配置值不在允许的枚举列表中，允许值: %s", allowedValues);
            throw new ConfigValidationException(msg);
        }
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public String getErrorMessage() {
        if (errorMessage != null) {
            return errorMessage;
        }
        
        List<String> allowedValues = new ArrayList<>();
        if (params.containsKey("values")) {
            Object valuesObj = params.get("values");
            if (valuesObj instanceof List) {
                for (Object item : (List<Object>) valuesObj) {
                    allowedValues.add(String.valueOf(item));
                }
            }
        }
        
        return "配置值不在允许的枚举列表中，允许值: " + allowedValues;
    }
    
    private void logWarn(String message) {
        System.out.println("[WARN] " + message);
    }
}