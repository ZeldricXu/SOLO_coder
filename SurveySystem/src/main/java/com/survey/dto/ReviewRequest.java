package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRequest {

    @NotBlank(message = "答卷ID不能为空")
    private String answerId;

    @NotBlank(message = "审核状态不能为空")
    private String reviewStatus;

    private String reviewComment;

    private String reviewerId;
}
