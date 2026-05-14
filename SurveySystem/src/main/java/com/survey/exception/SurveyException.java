package com.survey.exception;

import lombok.Getter;

@Getter
public class SurveyException extends RuntimeException {

    private final Integer code;

    public SurveyException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public SurveyException(String message) {
        super(message);
        this.code = 500;
    }

    public static SurveyException surveyNotFound(String surveyId) {
        return new SurveyException(404, "问卷不存在: " + surveyId);
    }

    public static SurveyException surveyClosed(String surveyId) {
        return new SurveyException(400, "问卷已关闭: " + surveyId);
    }

    public static SurveyException surveyExpired(String surveyId) {
        return new SurveyException(400, "问卷已过期: " + surveyId);
    }

    public static SurveyException answerIncomplete(String message) {
        return new SurveyException(400, "答卷不完整: " + message);
    }

    public static SurveyException answerTypeError(String questionId) {
        return new SurveyException(400, "答案类型错误，题目ID: " + questionId);
    }

    public static SurveyException answerNotFound(String answerId) {
        return new SurveyException(404, "答卷不存在: " + answerId);
    }

    public static SurveyException publishFailed(String message) {
        return new SurveyException(500, "问卷发布失败: " + message);
    }
}
