package com.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewExecuteRequest {
    @NotBlank(message = "面试ID不能为空")
    private String interviewId;

    private Boolean passed;
    private Integer interviewScore;
    private String interviewResult;
    private String techEvaluation;
    private String overallEvaluation;
    private String rejectReason;
}
