package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublishConfirmRequest {

    @NotBlank(message = "发布ID不能为空")
    private String publishId;

    @NotBlank(message = "确认状态不能为空")
    private String confirmStatus;

    private String confirmMessage;

    private String confirmedBy;
}
