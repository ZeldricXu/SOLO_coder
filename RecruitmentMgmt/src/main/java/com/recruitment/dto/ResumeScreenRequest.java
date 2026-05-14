package com.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeScreenRequest {
    @NotBlank(message = "简历ID不能为空")
    private String resumeId;

    private Boolean passed;
    private String screenResult;
    private String rejectReason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResumeCheckResult {
        private boolean passed;
        private boolean positionStatusValid;
        private boolean duplicateCheckPassed;
        private boolean availabilityCheckPassed;
        private boolean historyCheckPassed;
        private String errorMessage;
        private List<String> errors;
    }
}
