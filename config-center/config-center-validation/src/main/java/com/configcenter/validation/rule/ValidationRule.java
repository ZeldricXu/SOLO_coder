package com.configcenter.validation.rule;

import com.configcenter.common.exception.ConfigValidationException;

import java.util.Map;

public interface ValidationRule {
    
    String getRuleId();
    
    String getRuleType();
    
    String getName();
    
    String getDescription();
    
    boolean isEnabled();
    
    int getPriority();
    
    Map<String, Object> getParams();
    
    void validate(String value, Map<String, Object> context) throws ConfigValidationException;
    
    default String getErrorMessage() {
        return "校验失败: " + getName();
    }
}
