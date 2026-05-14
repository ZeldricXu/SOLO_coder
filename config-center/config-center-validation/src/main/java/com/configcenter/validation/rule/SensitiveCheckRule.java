package com.configcenter.validation.rule;

import com.configcenter.common.exception.ConfigValidationException;
import lombok.Builder;
import lombok.Data;

import java.util.*;

@Data
@Builder
public class SensitiveCheckRule implements ValidationRule {
    
    @Builder.Default
    private String ruleId = "RULE_SENSITIVE_CHECK";
    
    @Builder.Default
    private String ruleType = "SENSITIVE_CHECK";
    
    @Builder.Default
    private String name = "敏感词检查";
    
    @Builder.Default
    private String description = "检查配置值中是否包含敏感关键词";
    
    @Builder.Default
    private boolean enabled = true;
    
    @Builder.Default
    private int priority = 500;
    
    @Builder.Default
    private Map<String, Object> params = new HashMap<>();
    
    @Builder.Default
    private List<String> defaultKeywords = Arrays.asList("password", "secret", "token", "key");
    
    @Builder.Default
    private boolean defaultWarnOnly = true;

    @Override
    public void validate(String value, Map<String, Object> context) throws ConfigValidationException {
        if (value == null || value.isEmpty()) {
            return;
        }
        
        boolean warnOnly = params.containsKey("warnOnly") ? 
                (Boolean) params.get("warnOnly") : defaultWarnOnly;
        
        List<String> keywords = params.containsKey("keywords") ? 
                (List<String>) params.get("keywords") : defaultKeywords;
        
        String lowerValue = value.toLowerCase();
        List<String> foundKeywords = new ArrayList<>();
        
        for (String keyword : keywords) {
            if (lowerValue.contains(keyword.toLowerCase())) {
                foundKeywords.add(keyword);
            }
        }
        
        if (!foundKeywords.isEmpty()) {
            String message = "检测到敏感关键词: " + String.join(", ", foundKeywords);
            if (warnOnly) {
                if (context != null) {
                    context.put("warning_" + ruleId, message);
                }
            } else {
                throw new ConfigValidationException(message);
            }
        }
    }

    @Override
    public String getErrorMessage() {
        List<String> keywords = params.containsKey("keywords") ? 
                (List<String>) params.get("keywords") : defaultKeywords;
        return "配置值包含敏感关键词: " + String.join(", ", keywords);
    }
}
