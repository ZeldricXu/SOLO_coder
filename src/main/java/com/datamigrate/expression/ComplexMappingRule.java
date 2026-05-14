package com.datamigrate.expression;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplexMappingRule implements Serializable {

    private static final long serialVersionUID = 1L;

    private String ruleId;
    private String targetField;
    
    private String sourceExpression;
    
    private String condition;
    
    private String transformation;
    
    private String defaultValue;
    
    @Builder.Default
    private List<FieldMapping> fieldMappings = new ArrayList<>();
    
    @Builder.Default
    private List<ValueMapping> valueMappings = new ArrayList<>();
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldMapping {
        private String sourceField;
        private String operation;
        private String targetPart;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValueMapping {
        private String sourceValue;
        private String targetValue;
        private boolean isRegex;
    }

    public static ComplexMappingRule fromString(String ruleString) {
        if (ruleString == null || ruleString.trim().isEmpty()) {
            return null;
        }

        String[] parts = ruleString.split("=>");
        if (parts.length < 2) {
            return null;
        }

        String targetPart = parts[1].trim();
        String sourcePart = parts[0].trim();

        ComplexMappingRule rule = new ComplexMappingRule();
        rule.setTargetField(targetPart);
        rule.setSourceExpression(sourcePart);

        return rule;
    }
}
