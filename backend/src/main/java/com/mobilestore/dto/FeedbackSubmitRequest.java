package com.mobilestore.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FeedbackSubmitRequest {

    @NotBlank(message = "应用ID不能为空")
    private String appId;

    private String userId;

    private String feedbackType;

    @NotBlank(message = "反馈内容不能为空")
    private String content;

    private Integer rating;

    private String title;
}
