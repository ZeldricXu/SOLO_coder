package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SurveyCreateRequest {

    @NotBlank(message = "问卷名称不能为空")
    private String surveyName;

    @NotBlank(message = "问卷类型不能为空")
    private String surveyType;

    private String surveyDescription;

    @NotEmpty(message = "问卷题目不能为空")
    private List<QuestionItem> surveyQuestions;

    private LocalDateTime surveyDeadline;

    private String templateId;

    private Boolean needReview = false;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionItem {
        @NotBlank(message = "题目类型不能为空")
        private String questionType;
        @NotBlank(message = "题目内容不能为空")
        private String questionContent;
        private List<String> options;
        private Boolean required = true;
    }
}
