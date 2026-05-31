package com.datastandard.modules.core;

import com.datastandard.modules.core.dto.StandardizationConfig;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface StandardizationRule {

    String getRuleName();

    Mono<Map<String, Object>> apply(Map<String, Object> record, StandardizationConfig.FieldRule rule, ProcessingContext context);

    default boolean supports(StandardizationConfig.FieldRule rule) {
        return true;
    }

    @Component
    class TrimRule implements StandardizationRule {
        @Override
        public String getRuleName() {
            return "trim";
        }

        @Override
        public Mono<Map<String, Object>> apply(Map<String, Object> record, StandardizationConfig.FieldRule rule, ProcessingContext context) {
            if (rule.isTrim() && record.containsKey(rule.getSourceField())) {
                Object value = record.get(rule.getSourceField());
                if (value instanceof String) {
                    record.put(rule.getTargetField(), ((String) value).trim());
                }
            }
            return Mono.just(record);
        }

        @Override
        public boolean supports(StandardizationConfig.FieldRule rule) {
            return rule.isTrim();
        }
    }

    @Component
    class LowercaseRule implements StandardizationRule {
        @Override
        public String getRuleName() {
            return "lowercase";
        }

        @Override
        public Mono<Map<String, Object>> apply(Map<String, Object> record, StandardizationConfig.FieldRule rule, ProcessingContext context) {
            if (rule.isLowercase() && record.containsKey(rule.getSourceField())) {
                Object value = record.get(rule.getSourceField());
                if (value instanceof String) {
                    record.put(rule.getTargetField(), ((String) value).toLowerCase());
                }
            }
            return Mono.just(record);
        }

        @Override
        public boolean supports(StandardizationConfig.FieldRule rule) {
            return rule.isLowercase();
        }
    }

    @Component
    class UppercaseRule implements StandardizationRule {
        @Override
        public String getRuleName() {
            return "uppercase";
        }

        @Override
        public Mono<Map<String, Object>> apply(Map<String, Object> record, StandardizationConfig.FieldRule rule, ProcessingContext context) {
            if (rule.isUppercase() && record.containsKey(rule.getSourceField())) {
                Object value = record.get(rule.getSourceField());
                if (value instanceof String) {
                    record.put(rule.getTargetField(), ((String) value).toUpperCase());
                }
            }
            return Mono.just(record);
        }

        @Override
        public boolean supports(StandardizationConfig.FieldRule rule) {
            return rule.isUppercase();
        }
    }

    @Component
    class DefaultValueRule implements StandardizationRule {
        @Override
        public String getRuleName() {
            return "defaultValue";
        }

        @Override
        public Mono<Map<String, Object>> apply(Map<String, Object> record, StandardizationConfig.FieldRule rule, ProcessingContext context) {
            if (!record.containsKey(rule.getSourceField()) || record.get(rule.getSourceField()) == null) {
                if (rule.getDefaultValue() != null) {
                    record.put(rule.getTargetField(), rule.getDefaultValue());
                }
            }
            return Mono.just(record);
        }

        @Override
        public boolean supports(StandardizationConfig.FieldRule rule) {
            return rule.getDefaultValue() != null;
        }
    }

    @Component
    class PatternReplaceRule implements StandardizationRule {
        @Override
        public String getRuleName() {
            return "patternReplace";
        }

        @Override
        public Mono<Map<String, Object>> apply(Map<String, Object> record, StandardizationConfig.FieldRule rule, ProcessingContext context) {
            if (rule.getPattern() != null && record.containsKey(rule.getSourceField())) {
                Object value = record.get(rule.getSourceField());
                if (value instanceof String str) {
                    Map<String, Object> params = rule.getParams();
                    if (params != null && params.containsKey("replacement")) {
                        String replacement = String.valueOf(params.get("replacement"));
                        record.put(rule.getTargetField(), str.replaceAll(rule.getPattern(), replacement));
                    }
                }
            }
            return Mono.just(record);
        }

        @Override
        public boolean supports(StandardizationConfig.FieldRule rule) {
            return rule.getPattern() != null;
        }
    }

    @Component
    class FieldMappingRule implements StandardizationRule {
        @Override
        public String getRuleName() {
            return "fieldMapping";
        }

        @Override
        public Mono<Map<String, Object>> apply(Map<String, Object> record, StandardizationConfig.FieldRule rule, ProcessingContext context) {
            if (!rule.getSourceField().equals(rule.getTargetField()) && record.containsKey(rule.getSourceField())) {
                record.put(rule.getTargetField(), record.get(rule.getSourceField()));
            }
            return Mono.just(record);
        }
    }

    @Component
    class ValidationRule implements StandardizationRule {
        @Override
        public String getRuleName() {
            return "validation";
        }

        @Override
        public Mono<Map<String, Object>> apply(Map<String, Object> record, StandardizationConfig.FieldRule rule, ProcessingContext context) {
            Object value = record.get(rule.getSourceField());

            if (rule.isRequired() && (value == null || (value instanceof String && ((String) value).isEmpty()))) {
                context.addError(rule.getSourceField(), String.valueOf(value), "REQUIRED_FIELD_MISSING",
                        "字段 '" + rule.getSourceField() + "' 是必填项");
                return Mono.just(record);
            }

            if (value instanceof String str) {
                if (rule.getMaxLength() != null && str.length() > rule.getMaxLength()) {
                    context.addError(rule.getSourceField(), str, "MAX_LENGTH_EXCEEDED",
                            "字段 '" + rule.getSourceField() + "' 超过最大长度 " + rule.getMaxLength());
                }
                if (rule.getMinLength() != null && str.length() < rule.getMinLength()) {
                    context.addError(rule.getSourceField(), str, "MIN_LENGTH_NOT_MET",
                            "字段 '" + rule.getSourceField() + "' 不满足最小长度 " + rule.getMinLength());
                }
                if (rule.getRegex() != null && !str.matches(rule.getRegex())) {
                    context.addError(rule.getSourceField(), str, "REGEX_MISMATCH",
                            "字段 '" + rule.getSourceField() + "' 不匹配正则表达式 " + rule.getRegex());
                }
                if (rule.getAllowedValues() != null && !rule.getAllowedValues().contains(str)) {
                    context.addError(rule.getSourceField(), str, "VALUE_NOT_ALLOWED",
                            "字段 '" + rule.getSourceField() + "' 的值不在允许列表中");
                }
            }

            return Mono.just(record);
        }

        @Override
        public boolean supports(StandardizationConfig.FieldRule rule) {
            return rule.isRequired() || rule.getMaxLength() != null || rule.getMinLength() != null
                    || rule.getRegex() != null || rule.getAllowedValues() != null;
        }
    }
}
