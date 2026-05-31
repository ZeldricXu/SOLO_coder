package com.taskflow.flow.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ValidationResult {
    private boolean valid;
    private List<ValidationError> errors;
    private List<ValidationWarning> warnings;

    @Data
    @Builder
    public static class ValidationError {
        private String code;
        private String message;
        private String nodeId;
        private String edgeId;
    }

    @Data
    @Builder
    public static class ValidationWarning {
        private String code;
        private String message;
        private String nodeId;
        private String edgeId;
    }
}
