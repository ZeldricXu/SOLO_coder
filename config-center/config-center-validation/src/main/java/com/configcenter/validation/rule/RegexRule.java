package com.configcenter.validation.rule;

import com.configcenter.common.exception.ConfigValidationException;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Data
@Builder
public class RegexRule implements ValidationRule {
    
    @Builder.Default
    private String ruleId = "RULE_REGEX";
    
    @Builder.Default
    private String ruleType = "REGEX";
    
    @Builder.Default
    private String name = "正则表达式校验";
    
    @Builder.Default
    private String description = "使用正则表达式校验配置值格式";
    
    @Builder.Default
    private boolean enabled = true;
    
    @Builder.Default
    private int priority = 300;
    
    @Builder.Default
    private Map<String, Object> params = new HashMap<>();
    
    @Builder.Default
    private String defaultPattern = ".*";
    
    private String errorMessage;
    
    private Pattern compiledPattern;
    
    @Override
    public void validate(String value, Map<String, Object> context) throws ConfigValidationException {
        if (value == null) {
            throw new ConfigValidationException("配置值不能为空");
        }
        
        String patternStr = params.containsKey("pattern") ? 
                String.valueOf(params.get("pattern")) : defaultPattern;
        
        boolean caseInsensitive = params.containsKey("caseInsensitive") && 
                Boolean.parseBoolean(String.valueOf(params.get("caseInsensitive")));
        
        if (compiledPattern == null || !compiledPattern.pattern().equals(patternStr)) {
            try {
                int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
                compiledPattern = Pattern.compile(patternStr, flags);
            } catch (PatternSyntaxException e) {
                throw new ConfigValidationException("正则表达式格式错误: " + e.getMessage());
            }
        }
        
        if (!compiledPattern.matcher(value).matches()) {
            String msg = errorMessage != null ? errorMessage : 
                    String.format("配置值不匹配正则表达式: %s", patternStr);
            throw new ConfigValidationException(msg);
        }
    }
    
    @Override
    public String getErrorMessage() {
        if (errorMessage != null) {
            return errorMessage;
        }
        String patternStr = params.containsKey("pattern") != null ? 
                String.valueOf(params.get("pattern")) : defaultPattern;
        return "配置值不匹配正则表达式: " + patternStr;
    }
}