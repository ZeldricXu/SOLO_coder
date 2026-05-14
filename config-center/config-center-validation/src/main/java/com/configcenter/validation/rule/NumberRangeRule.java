package com.configcenter.validation.rule;

import com.configcenter.common.exception.ConfigValidationException;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class NumberRangeRule implements ValidationRule {
    
    @Builder.Default
    private String ruleId = "RULE_NUMBER_RANGE";
    
    @Builder.Default
    private String ruleType = "NUMBER_RANGE";
    
    @Builder.Default
    private String name = "数值范围校验";
    
    @Builder.Default
    private String description = "校验数值配置值是否在指定范围内";
    
    @Builder.Default
    private boolean enabled = true;
    
    @Builder.Default
    private int priority = 400;
    
    @Builder.Default
    private Map<String, Object> params = new HashMap<>();
    
    @Builder.Default
    private BigDecimal defaultMin = new BigDecimal(Integer.MIN_VALUE);
    
    @Builder.Default
    private BigDecimal defaultMax = new BigDecimal(Integer.MAX_VALUE);

    @Override
    public void validate(String value, Map<String, Object> context) throws ConfigValidationException {
        if (value == null || value.isEmpty()) {
            throw new ConfigValidationException("数值配置值不能为空");
        }
        
        BigDecimal number;
        try {
            number = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new ConfigValidationException("数值格式错误: " + value, e);
        }
        
        BigDecimal min = params.containsKey("min") ? 
                new BigDecimal(String.valueOf(params.get("min"))) : defaultMin;
        BigDecimal max = params.containsKey("max") ? 
                new BigDecimal(String.valueOf(params.get("max"))) : defaultMax;
        
        if (number.compareTo(min) < 0) {
            throw new ConfigValidationException(
                    "数值不能小于 " + min + "，当前值: " + number);
        }
        
        if (number.compareTo(max) > 0) {
            throw new ConfigValidationException(
                    "数值不能超过 " + max + "，当前值: " + number);
        }
    }

    @Override
    public String getErrorMessage() {
        BigDecimal min = params.containsKey("min") ? 
                new BigDecimal(String.valueOf(params.get("min"))) : defaultMin;
        BigDecimal max = params.containsKey("max") ? 
                new BigDecimal(String.valueOf(params.get("max"))) : defaultMax;
        return "数值必须在 [" + min + ", " + max + "] 范围内";
    }
}
