package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublishRequest {

    @NotBlank(message = "问卷ID不能为空")
    private String surveyId;

    @NotBlank(message = "发布渠道不能为空")
    private String publishChannel;

    @NotBlank(message = "发布范围不能为空")
    private String publishRange;

    private java.util.List<String> targetEmails;

    private java.util.List<String> targetUserIds;

    private Integer maxRetryCount;

    private Boolean needConfirm = true;
}
