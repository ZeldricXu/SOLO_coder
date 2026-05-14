package com.configcenter.validation.rule;

import com.configcenter.common.exception.ConfigValidationException;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Data
@Builder
public class KeyFormatRule implements ValidationRule {
    
    @Builder.Default
    private String ruleId = "RULE_KEY_FORMAT";
    
    @Builder.Default
    private String ruleType = "KEY_FORMAT";
    
    @Builder.Default
    private String name = "配置键格式校验";
    
    @Builder.Default
    private String description = "校验配置键的格式是否符合规范";
    
    @Builder.Default
    private boolean enabled = true;
    
    @Builder.Default
    private int priority = 100;
    
    @Builder.Default
    private Map<String, Object> params = new HashMap<>();
    
    @Builder.Default
    private String defaultPattern = "^[a-zA-Z][a-zA-Z0-9._-]*$";

    @Override
    public void validate(String value, Map<String, Object> context) throws ConfigValidationException {
        if (value == null || value.isEmpty()) {
            throw new ConfigValidationException("配置键不能为空");
        }
        
        String pattern = params.containsKey("pattern") ? 
                String.valueOf(params.get("pattern")) : defaultPattern;
        
        if (!Pattern.matches(pattern, value)) {
            throw new ConfigValidationException(
                    "配置键格式错误，必须匹配正则: " + pattern);
        }
    }

    @Override
    public String getErrorMessage() {
        String pattern = params.containsKey("pattern") ? 
                String.valueOf(params.get("pattern")) : defaultPattern;
        return "配置键格式错误，必须匹配正则: " + pattern;
    }
}
