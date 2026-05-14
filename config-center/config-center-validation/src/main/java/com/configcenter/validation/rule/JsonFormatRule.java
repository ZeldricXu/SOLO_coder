package com.configcenter.validation.rule;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.configcenter.common.exception.ConfigValidationException;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class JsonFormatRule implements ValidationRule {
    
    @Builder.Default
    private String ruleId = "RULE_JSON_FORMAT";
    
    @Builder.Default
    private String ruleType = "JSON_FORMAT";
    
    @Builder.Default
    private String name = "JSON格式校验";
    
    @Builder.Default
    private String description = "校验配置值是否为有效的JSON格式";
    
    @Builder.Default
    private boolean enabled = true;
    
    @Builder.Default
    private int priority = 300;
    
    @Builder.Default
    private Map<String, Object> params = new HashMap<>();
    
    @Builder.Default
    private boolean defaultAllowEmpty = false;

    @Override
    public void validate(String value, Map<String, Object> context) throws ConfigValidationException {
        boolean allowEmpty = params.containsKey("allowEmpty") ? 
                (Boolean) params.get("allowEmpty") : defaultAllowEmpty;
        
        if (value == null || value.isEmpty()) {
            if (allowEmpty) {
                return;
            }
            throw new ConfigValidationException("JSON配置值不能为空");
        }
        
        try {
            JSON.parse(value);
        } catch (JSONException e) {
            throw new ConfigValidationException("JSON格式错误: " + e.getMessage(), e);
        }
    }

    @Override
    public String getErrorMessage() {
        return "配置值必须是有效的JSON格式";
    }
}
