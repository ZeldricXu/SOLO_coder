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
public class ResumeSubmitRequest {
    @NotBlank(message = "职位ID不能为空")
    private String positionId;

    @NotBlank(message = "候选人姓名不能为空")
    private String candidateName;

    @NotBlank(message = "候选人电话不能为空")
    private String candidatePhone;

    private String candidateEmail;
    private String candidateEducation;
    private String candidateExperience;
    private String resumeSource;
}
