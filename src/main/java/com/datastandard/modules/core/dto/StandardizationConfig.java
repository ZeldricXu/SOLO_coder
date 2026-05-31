package com.datastandard.modules.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardizationConfig {

    private String configId;

    private String configVersion;

    @NotEmpty(message = "字段规则配置不能为空")
    @Valid
    private List<FieldRule> fieldRules;

    private boolean enableDataCleaning;

    private boolean enableTypeConversion;

    private boolean enableValidation;

    private boolean failOnError;

    private int maxParallelism;

    private long timeoutMs;

    private Map<String, Object> customConfig;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldRule {
        private String sourceField;
        private String targetField;
        private String targetType;
        private boolean required;
        private boolean trim;
        private boolean lowercase;
        private boolean uppercase;
        private String dateFormat;
        private String pattern;
        private String defaultValue;
        private Integer maxLength;
        private Integer minLength;
        private String regex;
        private List<String> allowedValues;
        private Map<String, Object> params;
    }
}
