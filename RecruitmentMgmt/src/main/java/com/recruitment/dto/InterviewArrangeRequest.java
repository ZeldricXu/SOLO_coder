package com.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewArrangeRequest {
    @NotBlank(message = "简历ID不能为空")
    private String resumeId;

    @NotBlank(message = "面试官ID不能为空")
    private String interviewerId;

    @NotNull(message = "面试时间不能为空")
    private Instant interviewTime;

    private String interviewType;
}
