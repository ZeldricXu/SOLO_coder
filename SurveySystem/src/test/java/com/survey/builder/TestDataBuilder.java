package com.survey.builder;

import com.survey.common.SurveyConstants;
import com.survey.dto.*;
import com.survey.entity.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    private TestDataBuilder() {
    }

    public static String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static SurveyBuilder surveyBuilder() {
        return new SurveyBuilder();
    }

    public static QuestionBuilder questionBuilder() {
        return new QuestionBuilder();
    }

    public static PublishRecordBuilder publishRecordBuilder() {
        return new PublishRecordBuilder();
    }

    public static PublishRequestBuilder publishRequestBuilder() {
        return new PublishRequestBuilder();
    }

    public static AnswerRecordBuilder answerRecordBuilder() {
        return new AnswerRecordBuilder();
    }

    public static AnswerSubmitRequestBuilder answerSubmitRequestBuilder() {
        return new AnswerSubmitRequestBuilder();
    }

    public static StatRecordBuilder statRecordBuilder() {
        return new StatRecordBuilder();
    }

    public static SurveyTypeBuilder surveyTypeBuilder() {
        return new SurveyTypeBuilder();
    }

    public static SurveyCreateRequestBuilder surveyCreateRequestBuilder() {
        return new SurveyCreateRequestBuilder();
    }

    public static ReviewRequestBuilder reviewRequestBuilder() {
        return new ReviewRequestBuilder();
    }

    public static ReviewRecordBuilder reviewRecordBuilder() {
        return new ReviewRecordBuilder();
    }

    public static class SurveyBuilder {
        private String surveyId = generateId("survey");
        private String surveyName = "测试问卷";
        private String surveyType = "satisfaction";
        private String surveyDescription = "这是一个测试问卷";
        private String surveyStatus = SurveyConstants.SURVEY_STATUS_DRAFT;
        private LocalDateTime surveyDeadline = LocalDateTime.now().plusDays(7);
        private String templateId = null;
        private Boolean needReview = false;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt = null;
        private LocalDateTime publishedAt = null;
        private List<Question> surveyQuestions = new ArrayList<>();

        public SurveyBuilder surveyId(String surveyId) {
            this.surveyId = surveyId;
            return this;
        }

        public SurveyBuilder surveyName(String surveyName) {
            this.surveyName = surveyName;
            return this;
        }

        public SurveyBuilder surveyType(String surveyType) {
            this.surveyType = surveyType;
            return this;
        }

        public SurveyBuilder surveyDescription(String surveyDescription) {
            this.surveyDescription = surveyDescription;
            return this;
        }

        public SurveyBuilder surveyStatus(String surveyStatus) {
            this.surveyStatus = surveyStatus;
            return this;
        }

        public SurveyBuilder surveyDeadline(LocalDateTime surveyDeadline) {
            this.surveyDeadline = surveyDeadline;
            return this;
        }

        public SurveyBuilder templateId(String templateId) {
            this.templateId = templateId;
            return this;
        }

        public SurveyBuilder needReview(Boolean needReview) {
            this.needReview = needReview;
            return this;
        }

        public SurveyBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public SurveyBuilder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public SurveyBuilder publishedAt(LocalDateTime publishedAt) {
            this.publishedAt = publishedAt;
            return this;
        }

        public SurveyBuilder surveyQuestions(List<Question> surveyQuestions) {
            this.surveyQuestions = surveyQuestions;
            return this;
        }

        public Survey build() {
            Survey survey = new Survey();
            survey.setSurveyId(surveyId);
            survey.setSurveyName(surveyName);
            survey.setSurveyType(surveyType);
            survey.setSurveyDescription(surveyDescription);
            survey.setSurveyStatus(surveyStatus);
            survey.setSurveyDeadline(surveyDeadline);
            survey.setTemplateId(templateId);
            survey.setNeedReview(needReview);
            survey.setCreatedAt(createdAt);
            survey.setUpdatedAt(updatedAt);
            survey.setPublishedAt(publishedAt);
            survey.setSurveyQuestions(surveyQuestions);
            return survey;
        }

        public Survey buildDraftSurvey() {
            return surveyBuilder()
                    .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                    .build();
        }

        public Survey buildPendingSurvey() {
            return surveyBuilder()
                    .surveyStatus(SurveyConstants.SURVEY_STATUS_PENDING)
                    .build();
        }

        public Survey buildPublishedSurvey() {
            return surveyBuilder()
                    .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                    .publishedAt(LocalDateTime.now())
                    .build();
        }

        public Survey buildClosedSurvey() {
            return surveyBuilder()
                    .surveyStatus(SurveyConstants.SURVEY_STATUS_CLOSED)
                    .build();
        }

        public Survey buildExpiredSurvey() {
            return surveyBuilder()
                    .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                    .surveyDeadline(LocalDateTime.now().minusDays(1))
                    .build();
        }

        public Survey buildSurveyWithQuestions() {
            Survey survey = buildDraftSurvey();
            List<Question> questions = Arrays.asList(
                    questionBuilder().questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                            .questionContent("您对我们的服务满意吗？")
                            .options(Arrays.asList("非常满意", "满意", "一般", "不满意"))
                            .buildWithSurvey(survey),
                    questionBuilder().questionType(SurveyConstants.QUESTION_TYPE_RATING)
                            .questionContent("请为我们的服务打分（1-5分）")
                            .buildWithSurvey(survey),
                    questionBuilder().questionType(SurveyConstants.QUESTION_TYPE_TEXT)
                            .questionContent("您有什么建议吗？")
                            .required(false)
                            .buildWithSurvey(survey)
            );
            survey.setSurveyQuestions(questions);
            return survey;
        }
    }

    public static class QuestionBuilder {
        private String questionId = generateId("q");
        private String questionType = SurveyConstants.QUESTION_TYPE_SINGLE;
        private String questionContent = "测试题目";
        private List<String> options = new ArrayList<>();
        private Boolean required = true;
        private Integer questionOrder = 1;
        private Survey survey = null;

        public QuestionBuilder questionId(String questionId) {
            this.questionId = questionId;
            return this;
        }

        public QuestionBuilder questionType(String questionType) {
            this.questionType = questionType;
            return this;
        }

        public QuestionBuilder questionContent(String questionContent) {
            this.questionContent = questionContent;
            return this;
        }

        public QuestionBuilder options(List<String> options) {
            this.options = options;
            return this;
        }

        public QuestionBuilder required(Boolean required) {
            this.required = required;
            return this;
        }

        public QuestionBuilder questionOrder(Integer questionOrder) {
            this.questionOrder = questionOrder;
            return this;
        }

        public QuestionBuilder survey(Survey survey) {
            this.survey = survey;
            return this;
        }

        public Question build() {
            Question question = new Question();
            question.setQuestionId(questionId);
            question.setQuestionType(questionType);
            question.setQuestionContent(questionContent);
            question.setOptions(options);
            question.setRequired(required);
            question.setQuestionOrder(questionOrder);
            question.setSurvey(survey);
            return question;
        }

        public Question buildWithSurvey(Survey survey) {
            this.survey = survey;
            return build();
        }

        public Question buildSingleChoiceQuestion() {
            return questionBuilder()
                    .questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                    .options(Arrays.asList("选项A", "选项B", "选项C"))
                    .build();
        }

        public Question buildRatingQuestion() {
            return questionBuilder()
                    .questionType(SurveyConstants.QUESTION_TYPE_RATING)
                    .build();
        }

        public Question buildTextQuestion() {
            return questionBuilder()
                    .questionType(SurveyConstants.QUESTION_TYPE_TEXT)
                    .build();
        }
    }

    public static class PublishRecordBuilder {
        private String publishId = generateId("publish");
        private String surveyId = generateId("survey");
        private String publishChannel = SurveyConstants.PUBLISH_CHANNEL_EMAIL;
        private String publishRange = SurveyConstants.PUBLISH_RANGE_TARGET;
        private String publishStatus = SurveyConstants.PUBLISH_STATUS_PUBLISHED;
        private LocalDateTime publishTime = LocalDateTime.now();
        private Integer publishCount = 500;
        private String publishLink = "http://localhost:8080/survey/test";

        public PublishRecordBuilder publishId(String publishId) {
            this.publishId = publishId;
            return this;
        }

        public PublishRecordBuilder surveyId(String surveyId) {
            this.surveyId = surveyId;
            return this;
        }

        public PublishRecordBuilder publishChannel(String publishChannel) {
            this.publishChannel = publishChannel;
            return this;
        }

        public PublishRecordBuilder publishRange(String publishRange) {
            this.publishRange = publishRange;
            return this;
        }

        public PublishRecordBuilder publishStatus(String publishStatus) {
            this.publishStatus = publishStatus;
            return this;
        }

        public PublishRecordBuilder publishTime(LocalDateTime publishTime) {
            this.publishTime = publishTime;
            return this;
        }

        public PublishRecordBuilder publishCount(Integer publishCount) {
            this.publishCount = publishCount;
            return this;
        }

        public PublishRecordBuilder publishLink(String publishLink) {
            this.publishLink = publishLink;
            return this;
        }

        public PublishRecord build() {
            PublishRecord record = new PublishRecord();
            record.setPublishId(publishId);
            record.setSurveyId(surveyId);
            record.setPublishChannel(publishChannel);
            record.setPublishRange(publishRange);
            record.setPublishStatus(publishStatus);
            record.setPublishTime(publishTime);
            record.setPublishCount(publishCount);
            record.setPublishLink(publishLink);
            return record;
        }

        public PublishRecord buildEmailPublish() {
            return publishRecordBuilder()
                    .publishChannel(SurveyConstants.PUBLISH_CHANNEL_EMAIL)
                    .publishRange(SurveyConstants.PUBLISH_RANGE_ALL)
                    .publishCount(1000)
                    .build();
        }

        public PublishRecord buildLinkPublish() {
            return publishRecordBuilder()
                    .publishChannel(SurveyConstants.PUBLISH_CHANNEL_LINK)
                    .publishRange(SurveyConstants.PUBLISH_RANGE_TARGET)
                    .publishCount(500)
                    .build();
        }
    }

    public static class PublishRequestBuilder {
        private String surveyId = generateId("survey");
        private String publishChannel = SurveyConstants.PUBLISH_CHANNEL_EMAIL;
        private String publishRange = SurveyConstants.PUBLISH_RANGE_TARGET;

        public PublishRequestBuilder surveyId(String surveyId) {
            this.surveyId = surveyId;
            return this;
        }

        public PublishRequestBuilder publishChannel(String publishChannel) {
            this.publishChannel = publishChannel;
            return this;
        }

        public PublishRequestBuilder publishRange(String publishRange) {
            this.publishRange = publishRange;
            return this;
        }

        public PublishRequest build() {
            return new PublishRequest(surveyId, publishChannel, publishRange);
        }

        public PublishRequest buildEmailPublishRequest(String surveyId) {
            return publishRequestBuilder()
                    .surveyId(surveyId)
                    .publishChannel(SurveyConstants.PUBLISH_CHANNEL_EMAIL)
                    .publishRange(SurveyConstants.PUBLISH_RANGE_ALL)
                    .build();
        }

        public PublishRequest buildLinkPublishRequest(String surveyId) {
            return publishRequestBuilder()
                    .surveyId(surveyId)
                    .publishChannel(SurveyConstants.PUBLISH_CHANNEL_LINK)
                    .publishRange(SurveyConstants.PUBLISH_RANGE_TARGET)
                    .build();
        }
    }

    public static class AnswerRecordBuilder {
        private String answerId = generateId("answer");
        private String surveyId = generateId("survey");
        private String userId = generateId("user");
        private String answerStatus = SurveyConstants.ANSWER_STATUS_SUBMITTED;
        private LocalDateTime answerTime = LocalDateTime.now();
        private String reviewId = null;
        private List<AnswerData> answerData = new ArrayList<>();

        public AnswerRecordBuilder answerId(String answerId) {
            this.answerId = answerId;
            return this;
        }

        public AnswerRecordBuilder surveyId(String surveyId) {
            this.surveyId = surveyId;
            return this;
        }

        public AnswerRecordBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public AnswerRecordBuilder answerStatus(String answerStatus) {
            this.answerStatus = answerStatus;
            return this;
        }

        public AnswerRecordBuilder answerTime(LocalDateTime answerTime) {
            this.answerTime = answerTime;
            return this;
        }

        public AnswerRecordBuilder reviewId(String reviewId) {
            this.reviewId = reviewId;
            return this;
        }

        public AnswerRecordBuilder answerData(List<AnswerData> answerData) {
            this.answerData = answerData;
            return this;
        }

        public AnswerRecord build() {
            AnswerRecord record = new AnswerRecord();
            record.setAnswerId(answerId);
            record.setSurveyId(surveyId);
            record.setUserId(userId);
            record.setAnswerStatus(answerStatus);
            record.setAnswerTime(answerTime);
            record.setReviewId(reviewId);
            record.setAnswerData(answerData);
            return record;
        }

        public AnswerRecord buildSubmittedAnswer() {
            return answerRecordBuilder()
                    .answerStatus(SurveyConstants.ANSWER_STATUS_SUBMITTED)
                    .build();
        }

        public AnswerRecord buildReviewingAnswer() {
            return answerRecordBuilder()
                    .answerStatus(SurveyConstants.ANSWER_STATUS_REVIEWING)
                    .build();
        }

        public AnswerRecord buildReviewedAnswer() {
            return answerRecordBuilder()
                    .answerStatus(SurveyConstants.ANSWER_STATUS_REVIEWED)
                    .build();
        }

        public AnswerRecord buildRejectedAnswer() {
            return answerRecordBuilder()
                    .answerStatus(SurveyConstants.ANSWER_STATUS_REJECTED)
                    .build();
        }
    }

    public static class AnswerSubmitRequestBuilder {
        private String surveyId = generateId("survey");
        private String userId = generateId("user");
        private List<AnswerSubmitRequest.AnswerDataItem> answerData = new ArrayList<>();

        public AnswerSubmitRequestBuilder surveyId(String surveyId) {
            this.surveyId = surveyId;
            return this;
        }

        public AnswerSubmitRequestBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public AnswerSubmitRequestBuilder answerData(List<AnswerSubmitRequest.AnswerDataItem> answerData) {
            this.answerData = answerData;
            return this;
        }

        public AnswerSubmitRequest build() {
            return new AnswerSubmitRequest(surveyId, userId, answerData);
        }

        public AnswerSubmitRequest buildValidRequest(String surveyId) {
            List<AnswerSubmitRequest.AnswerDataItem> items = Arrays.asList(
                    new AnswerSubmitRequest.AnswerDataItem(generateId("q"), "选项A"),
                    new AnswerSubmitRequest.AnswerDataItem(generateId("q"), "5")
            );
            return answerSubmitRequestBuilder()
                    .surveyId(surveyId)
                    .userId(generateId("user"))
                    .answerData(items)
                    .build();
        }

        public AnswerSubmitRequest buildIncompleteRequest(String surveyId) {
            return answerSubmitRequestBuilder()
                    .surveyId(surveyId)
                    .answerData(new ArrayList<>())
                    .build();
        }
    }

    public static class StatRecordBuilder {
        private String statId = generateId("stat");
        private String surveyId = generateId("survey");
        private Integer statAnswerCount = 0;
        private Integer statReviewedCount = 0;
        private Double statCompletionRate = 0.0;
        private String statQuestionStat = "{}";
        private LocalDateTime updatedAt = LocalDateTime.now();

        public StatRecordBuilder statId(String statId) {
            this.statId = statId;
            return this;
        }

        public StatRecordBuilder surveyId(String surveyId) {
            this.surveyId = surveyId;
            return this;
        }

        public StatRecordBuilder statAnswerCount(Integer statAnswerCount) {
            this.statAnswerCount = statAnswerCount;
            return this;
        }

        public StatRecordBuilder statReviewedCount(Integer statReviewedCount) {
            this.statReviewedCount = statReviewedCount;
            return this;
        }

        public StatRecordBuilder statCompletionRate(Double statCompletionRate) {
            this.statCompletionRate = statCompletionRate;
            return this;
        }

        public StatRecordBuilder statQuestionStat(String statQuestionStat) {
            this.statQuestionStat = statQuestionStat;
            return this;
        }

        public StatRecordBuilder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public StatRecord build() {
            StatRecord record = new StatRecord();
            record.setStatId(statId);
            record.setSurveyId(surveyId);
            record.setStatAnswerCount(statAnswerCount);
            record.setStatReviewedCount(statReviewedCount);
            record.setStatCompletionRate(statCompletionRate);
            record.setStatQuestionStat(statQuestionStat);
            record.setUpdatedAt(updatedAt);
            return record;
        }

        public StatRecord buildWithAnswers(int count) {
            return statRecordBuilder()
                    .statAnswerCount(count)
                    .statReviewedCount(count)
                    .statCompletionRate(1.0)
                    .build();
        }

        public StatRecord buildWithPartialAnswers(int total, int reviewed) {
            double rate = total > 0 ? (double) reviewed / total : 0;
            return statRecordBuilder()
                    .statAnswerCount(total)
                    .statReviewedCount(reviewed)
                    .statCompletionRate(rate)
                    .build();
        }
    }

    public static class SurveyTypeBuilder {
        private String typeCode = "satisfaction";
        private String typeName = "满意度调查";
        private String typeDescription = "用于用户满意度调查";
        private String typeStatus = SurveyConstants.TYPE_STATUS_ACTIVE;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt = null;

        public SurveyTypeBuilder typeCode(String typeCode) {
            this.typeCode = typeCode;
            return this;
        }

        public SurveyTypeBuilder typeName(String typeName) {
            this.typeName = typeName;
            return this;
        }

        public SurveyTypeBuilder typeDescription(String typeDescription) {
            this.typeDescription = typeDescription;
            return this;
        }

        public SurveyTypeBuilder typeStatus(String typeStatus) {
            this.typeStatus = typeStatus;
            return this;
        }

        public SurveyType build() {
            SurveyType type = new SurveyType();
            type.setTypeCode(typeCode);
            type.setTypeName(typeName);
            type.setTypeDescription(typeDescription);
            type.setTypeStatus(typeStatus);
            type.setCreatedAt(createdAt);
            type.setUpdatedAt(updatedAt);
            return type;
        }

        public SurveyType buildActiveType() {
            return surveyTypeBuilder()
                    .typeStatus(SurveyConstants.TYPE_STATUS_ACTIVE)
                    .build();
        }

        public SurveyType buildInactiveType() {
            return surveyTypeBuilder()
                    .typeStatus(SurveyConstants.TYPE_STATUS_INACTIVE)
                    .build();
        }
    }

    public static class SurveyCreateRequestBuilder {
        private String surveyName = "测试问卷";
        private String surveyType = "satisfaction";
        private String surveyDescription = "测试问卷描述";
        private List<SurveyCreateRequest.QuestionItem> surveyQuestions = new ArrayList<>();
        private LocalDateTime surveyDeadline = LocalDateTime.now().plusDays(7);
        private String templateId = null;
        private Boolean needReview = false;

        public SurveyCreateRequestBuilder surveyName(String surveyName) {
            this.surveyName = surveyName;
            return this;
        }

        public SurveyCreateRequestBuilder surveyType(String surveyType) {
            this.surveyType = surveyType;
            return this;
        }

        public SurveyCreateRequestBuilder surveyDescription(String surveyDescription) {
            this.surveyDescription = surveyDescription;
            return this;
        }

        public SurveyCreateRequestBuilder surveyQuestions(List<SurveyCreateRequest.QuestionItem> surveyQuestions) {
            this.surveyQuestions = surveyQuestions;
            return this;
        }

        public SurveyCreateRequestBuilder surveyDeadline(LocalDateTime surveyDeadline) {
            this.surveyDeadline = surveyDeadline;
            return this;
        }

        public SurveyCreateRequestBuilder templateId(String templateId) {
            this.templateId = templateId;
            return this;
        }

        public SurveyCreateRequestBuilder needReview(Boolean needReview) {
            this.needReview = needReview;
            return this;
        }

        public SurveyCreateRequest build() {
            return new SurveyCreateRequest(surveyName, surveyType, surveyDescription, surveyQuestions, surveyDeadline, templateId, needReview);
        }

        public SurveyCreateRequest buildValidRequest() {
            List<SurveyCreateRequest.QuestionItem> questions = Arrays.asList(
                    new SurveyCreateRequest.QuestionItem(SurveyConstants.QUESTION_TYPE_SINGLE, "您满意吗？", Arrays.asList("是", "否"), true),
                    new SurveyCreateRequest.QuestionItem(SurveyConstants.QUESTION_TYPE_TEXT, "请提建议", null, false)
            );
            return surveyCreateRequestBuilder()
                    .surveyName("用户满意度调查")
                    .surveyType("satisfaction")
                    .surveyDescription("了解用户对服务的满意程度")
                    .surveyQuestions(questions)
                    .build();
        }

        public SurveyCreateRequest buildRequestWithReview() {
            return buildValidRequest().setNeedReview(true);
        }
    }

    public static class ReviewRequestBuilder {
        private String answerId = generateId("answer");
        private String reviewStatus = SurveyConstants.REVIEW_STATUS_APPROVED;
        private String reviewComment = "审核通过";
        private String reviewerId = generateId("reviewer");

        public ReviewRequestBuilder answerId(String answerId) {
            this.answerId = answerId;
            return this;
        }

        public ReviewRequestBuilder reviewStatus(String reviewStatus) {
            this.reviewStatus = reviewStatus;
            return this;
        }

        public ReviewRequestBuilder reviewComment(String reviewComment) {
            this.reviewComment = reviewComment;
            return this;
        }

        public ReviewRequestBuilder reviewerId(String reviewerId) {
            this.reviewerId = reviewerId;
            return this;
        }

        public ReviewRequest build() {
            return new ReviewRequest(answerId, reviewStatus, reviewComment, reviewerId);
        }

        public ReviewRequest buildApprovalRequest(String answerId) {
            return reviewRequestBuilder()
                    .answerId(answerId)
                    .reviewStatus(SurveyConstants.REVIEW_STATUS_APPROVED)
                    .reviewComment("答卷有效，审核通过")
                    .build();
        }

        public ReviewRequest buildRejectionRequest(String answerId) {
            return reviewRequestBuilder()
                    .answerId(answerId)
                    .reviewStatus(SurveyConstants.REVIEW_STATUS_REJECTED)
                    .reviewComment("答卷内容不符合要求")
                    .build();
        }
    }

    public static class ReviewRecordBuilder {
        private String reviewId = generateId("review");
        private String answerId = generateId("answer");
        private String reviewStatus = SurveyConstants.REVIEW_STATUS_PENDING;
        private String reviewComment = null;
        private String reviewerId = null;
        private LocalDateTime reviewTime = LocalDateTime.now();

        public ReviewRecordBuilder reviewId(String reviewId) {
            this.reviewId = reviewId;
            return this;
        }

        public ReviewRecordBuilder answerId(String answerId) {
            this.answerId = answerId;
            return this;
        }

        public ReviewRecordBuilder reviewStatus(String reviewStatus) {
            this.reviewStatus = reviewStatus;
            return this;
        }

        public ReviewRecordBuilder reviewComment(String reviewComment) {
            this.reviewComment = reviewComment;
            return this;
        }

        public ReviewRecordBuilder reviewerId(String reviewerId) {
            this.reviewerId = reviewerId;
            return this;
        }

        public ReviewRecordBuilder reviewTime(LocalDateTime reviewTime) {
            this.reviewTime = reviewTime;
            return this;
        }

        public ReviewRecord build() {
            ReviewRecord record = new ReviewRecord();
            record.setReviewId(reviewId);
            record.setAnswerId(answerId);
            record.setReviewStatus(reviewStatus);
            record.setReviewComment(reviewComment);
            record.setReviewerId(reviewerId);
            record.setReviewTime(reviewTime);
            return record;
        }

        public ReviewRecord buildPendingReview(String answerId) {
            return reviewRecordBuilder()
                    .answerId(answerId)
                    .reviewStatus(SurveyConstants.REVIEW_STATUS_PENDING)
                    .build();
        }

        public ReviewRecord buildApprovedReview(String answerId) {
            return reviewRecordBuilder()
                    .answerId(answerId)
                    .reviewStatus(SurveyConstants.REVIEW_STATUS_APPROVED)
                    .reviewComment("审核通过")
                    .reviewerId(generateId("reviewer"))
                    .build();
        }
    }
}
