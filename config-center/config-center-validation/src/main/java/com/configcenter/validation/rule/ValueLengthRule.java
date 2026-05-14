package com.configcenter.validation.rule;

import com.configcenter.common.exception.ConfigValidationException;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class ValueLengthRule implements ValidationRule {
    
    @Builder.Default
    private String ruleId = "RULE_VALUE_LENGTH";
    
    @Builder.Default
    private String ruleType = "VALUE_LENGTH";
    
    @Builder.Default
    private String name = "配置值长度校验";
    
    @Builder.Default
    private String description = "校验配置值的长度是否在指定范围内";
    
    @Builder.Default
    private boolean enabled = true;
    
    @Builder.Default
    private int priority = 200;
    
    @Builder.Default
    private Map<String, Object> params = new HashMap<>();
    
    @Builder.Default
    private int defaultMinLength = 1;
    
    @Builder.Default
    private int defaultMaxLength = 65535;

    @Override
    public void validate(String value, Map<String, Object> context) throws ConfigValidationException {
        if (value == null) {
            throw new ConfigValidationException("配置值不能为空");
        }
        
        int minLength = params.containsKey("minLength") ? 
                ((Number) params.get("minLength")).intValue() : defaultMinLength;
        int maxLength = params.containsKey("maxLength") ? 
                ((Number) params.get("maxLength")).intValue() : defaultMaxLength;
        
        int length = value.length();
        
        if (length < minLength) {
            throw new ConfigValidationException(
                    "配置值长度不能小于 " + minLength + "，当前长度: " + length);
        }
        
        if (length > maxLength) {
            throw new ConfigValidationException(
                    "配置值长度不能超过 " + maxLength + "，当前长度: " + length);
        }
    }

    @Override
    public String getErrorMessage() {
        int minLength = params.containsKey("minLength") ? 
                ((Number) params.get("minLength")).intValue() : defaultMinLength;
        int maxLength = params.containsKey("maxLength") ? 
                ((Number) params.get("maxLength")).intValue() : defaultMaxLength;
        return "配置值长度必须在 [" + minLength + ", " + maxLength + "] 范围内";
    }
}
