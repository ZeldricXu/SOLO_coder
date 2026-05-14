package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnswerSubmitRequest {

    @NotBlank(message = "问卷ID不能为空")
    private String surveyId;

    private String userId;

    @NotEmpty(message = "答卷数据不能为空")
    private List<AnswerDataItem> answerData;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerDataItem {
        @NotBlank(message = "题目ID不能为空")
        private String questionId;
        private String answerValue;
    }
}
