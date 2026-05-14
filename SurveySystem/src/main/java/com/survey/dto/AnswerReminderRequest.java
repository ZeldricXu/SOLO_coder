package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnswerReminderRequest {

    @NotBlank(message = "发布ID不能为空")
    private String publishId;

    private List<String> targetUserIds;

    private List<String> targetEmails;

    private Integer maxReminderCount;
}
