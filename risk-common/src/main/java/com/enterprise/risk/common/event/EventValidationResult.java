package com.enterprise.risk.common.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 事件校验结果类
 * 封装事件校验的成功/失败状态、错误信息、警告信息等
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventValidationResult implements Serializable {

    @JsonProperty("valid")
    @Builder.Default
    private Boolean valid = true;

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("validated_at")
    @Builder.Default
    private Long validatedAt = Instant.now().toEpochMilli();

    @JsonProperty("duration_ms")
    private Long durationMs;

    @JsonProperty("errors")
    @Builder.Default
    private List<ValidationError> errors = new ArrayList<>();

    @JsonProperty("warnings")
    @Builder.Default
    private List<ValidationWarning> warnings = new ArrayList<>();

    @JsonProperty("schema_version")
    private String schemaVersion;

    @JsonProperty("business_line")
    private String businessLine;

    /**
     * 校验错误详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationError implements Serializable {

        @JsonProperty("code")
        private String code;

        @JsonProperty("field")
        private String field;

        @JsonProperty("message")
        private String message;

        @JsonProperty("rejected_value")
        private Object rejectedValue;

        @JsonProperty("severity")
        @Builder.Default
        private ErrorSeverity severity = ErrorSeverity.ERROR;

        @JsonProperty("rule_id")
        private String ruleId;

        /**
         * 错误严重程度
         */
        public enum ErrorSeverity {
            WARNING,
            ERROR,
            FATAL
        }
    }

    /**
     * 校验警告详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationWarning implements Serializable {

        @JsonProperty("code")
        private String code;

        @JsonProperty("field")
        private String field;

        @JsonProperty("message")
        private String message;

        @JsonProperty("suggested_value")
        private Object suggestedValue;

        @JsonProperty("warning_type")
        private WarningType warningType;

        /**
         * 警告类型
         */
        public enum WarningType {
            DEPRECATED_FIELD,
            MISSING_OPTIONAL_FIELD,
            UNEXPECTED_FIELD,
            TYPE_COERCION,
            VALUE_TRUNCATED,
            OUTLIER_VALUE,
            PERFORMANCE
        }
    }

    /**
     * 标准错误码常量
     */
    public static final String ERR_REQUIRED_FIELD = "REQUIRED_FIELD_MISSING";
    public static final String ERR_INVALID_FORMAT = "INVALID_FORMAT";
    public static final String ERR_INVALID_TYPE = "INVALID_TYPE";
    public static final String ERR_VALUE_OUT_OF_RANGE = "VALUE_OUT_OF_RANGE";
    public static final String ERR_PATTERN_MISMATCH = "PATTERN_MISMATCH";
    public static final String ERR_DUPLICATE_EVENT = "DUPLICATE_EVENT";
    public static final String ERR_UNSUPPORTED_EVENT_TYPE = "UNSUPPORTED_EVENT_TYPE";
    public static final String ERR_SCHEMA_VALIDATION_FAILED = "SCHEMA_VALIDATION_FAILED";
    public static final String ERR_TIMESTAMP_INVALID = "TIMESTAMP_INVALID";
    public static final String ERR_TIMESTAMP_OUT_OF_RANGE = "TIMESTAMP_OUT_OF_RANGE";
    public static final String ERR_IP_INVALID = "IP_INVALID";
    public static final String ERR_ENTITY_ID_MISSING = "ENTITY_ID_MISSING";
    public static final String ERR_BUSINESS_LINE_INVALID = "BUSINESS_LINE_INVALID";

    /**
     * 创建成功校验结果
     */
    public static EventValidationResult success(String eventId) {
        return EventValidationResult.builder()
                .valid(true)
                .eventId(eventId)
                .build();
    }

    /**
     * 创建失败校验结果
     */
    public static EventValidationResult failure(String eventId, String errorCode, String field, String message) {
        ValidationError error = ValidationError.builder()
                .code(errorCode)
                .field(field)
                .message(message)
                .build();
        return EventValidationResult.builder()
                .valid(false)
                .eventId(eventId)
                .errors(List.of(error))
                .build();
    }

    /**
     * 添加错误
     */
    public void addError(String code, String field, String message) {
        addError(code, field, message, null, null);
    }

    /**
     * 添加错误（带完整信息）
     */
    public void addError(String code, String field, String message, Object rejectedValue, String ruleId) {
        if (this.errors == null) {
            this.errors = new ArrayList<>();
        }
        this.errors.add(ValidationError.builder()
                .code(code)
                .field(field)
                .message(message)
                .rejectedValue(rejectedValue)
                .ruleId(ruleId)
                .build());
        this.valid = false;
    }

    /**
     * 添加警告
     */
    public void addWarning(String code, String field, String message) {
        addWarning(code, field, message, null, null);
    }

    /**
     * 添加警告（带完整信息）
     */
    public void addWarning(String code, String field, String message, Object suggestedValue, ValidationWarning.WarningType warningType) {
        if (this.warnings == null) {
            this.warnings = new ArrayList<>();
        }
        this.warnings.add(ValidationWarning.builder()
                .code(code)
                .field(field)
                .message(message)
                .suggestedValue(suggestedValue)
                .warningType(warningType)
                .build());
    }

    /**
     * 是否有错误
     */
    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }

    /**
     * 是否有警告
     */
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    /**
     * 获取错误数量
     */
    public int getErrorCount() {
        return errors != null ? errors.size() : 0;
    }

    /**
     * 获取警告数量
     */
    public int getWarningCount() {
        return warnings != null ? warnings.size() : 0;
    }

    /**
     * 获取指定字段的所有错误
     */
    public List<ValidationError> getErrorsByField(String field) {
        if (errors == null || field == null) {
            return new ArrayList<>();
        }
        List<ValidationError> result = new ArrayList<>();
        for (ValidationError error : errors) {
            if (field.equals(error.getField())) {
                result.add(error);
            }
        }
        return result;
    }

    /**
     * 获取所有错误消息（拼接）
     */
    public String getAllErrorMessages() {
        if (!hasErrors()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errors.size(); i++) {
            ValidationError error = errors.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            if (error.getField() != null) {
                sb.append("[").append(error.getField()).append("] ");
            }
            sb.append(error.getMessage());
        }
        return sb.toString();
    }

    /**
     * 合并另一个校验结果
     */
    public void merge(EventValidationResult other) {
        if (other == null) {
            return;
        }
        if (other.getValid() != null && !other.getValid()) {
            this.valid = false;
        }
        if (other.hasErrors()) {
            if (this.errors == null) {
                this.errors = new ArrayList<>();
            }
            this.errors.addAll(other.getErrors());
        }
        if (other.hasWarnings()) {
            if (this.warnings == null) {
                this.warnings = new ArrayList<>();
            }
            this.warnings.addAll(other.getWarnings());
        }
    }
}
