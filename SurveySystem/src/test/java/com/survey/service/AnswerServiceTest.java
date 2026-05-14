package com.survey.service;

import com.survey.builder.TestDataBuilder;
import com.survey.common.SurveyConstants;
import com.survey.dto.AnswerSubmitRequest;
import com.survey.dto.AnswerSubmitResponse;
import com.survey.entity.*;
import com.survey.exception.SurveyException;
import com.survey.repository.AnswerDataRepository;
import com.survey.repository.AnswerRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("收集模块单元测试")
class AnswerServiceTest {

    @Mock
    private AnswerRecordRepository answerRecordRepository;

    @Mock
    private AnswerDataRepository answerDataRepository;

    @Mock
    private SurveyService surveyService;

    @Mock
    private ReviewService reviewService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private AnswerService answerService;

    @Test
    @DisplayName("测试答卷收集 - 成功提交答卷（无需审核）")
    void testSubmitAnswer_Success_NoReview() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .needReview(false)
                .build();

        Question q1 = TestDataBuilder.questionBuilder()
                .questionId("q_001")
                .questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                .questionContent("您满意吗？")
                .options(Arrays.asList("是", "否"))
                .required(true)
                .build();

        List<Question> questions = Arrays.asList(q1);

        List<AnswerSubmitRequest.AnswerDataItem> answerItems = Arrays.asList(
                new AnswerSubmitRequest.AnswerDataItem("q_001", "是")
        );

        AnswerSubmitRequest request = new AnswerSubmitRequest(surveyId, "user_001", answerItems);

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(publishedSurvey));
        when(surveyService.isSurveyActive(surveyId)).thenReturn(true);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerRecordRepository.save(any(AnswerRecord.class))).thenAnswer(invocation -> {
            AnswerRecord record = invocation.getArgument(0);
            record.setAnswerId("answer_001");
            return record;
        });
        when(answerDataRepository.saveAll(anyList())).thenReturn(new ArrayList<>());
        doNothing().when(statisticsService).updateStatistics(anyString());
        doNothing().when(historyService).recordAnswerHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        AnswerSubmitResponse result = answerService.submitAnswer(request);

        assertNotNull(result);
        assertNotNull(result.getAnswerId());
        assertEquals(SurveyConstants.ANSWER_STATUS_SUBMITTED, result.getStatus());
        verify(answerRecordRepository, times(1)).save(any(AnswerRecord.class));
        verify(statisticsService, times(1)).updateStatistics(surveyId);
        verify(historyService, times(1)).recordAnswerHistory(anyString(), eq("SUBMIT_ANSWER"), anyString(), any());
    }

    @Test
    @DisplayName("测试答卷收集 - 成功提交答卷（需要审核）")
    void testSubmitAnswer_Success_NeedReview() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .needReview(true)
                .build();

        Question q1 = TestDataBuilder.questionBuilder()
                .questionId("q_001")
                .questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                .options(Arrays.asList("是", "否"))
                .required(true)
                .build();

        List<Question> questions = Arrays.asList(q1);

        List<AnswerSubmitRequest.AnswerDataItem> answerItems = Arrays.asList(
                new AnswerSubmitRequest.AnswerDataItem("q_001", "是")
        );

        AnswerSubmitRequest request = new AnswerSubmitRequest(surveyId, "user_001", answerItems);

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(publishedSurvey));
        when(surveyService.isSurveyActive(surveyId)).thenReturn(true);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerRecordRepository.save(any(AnswerRecord.class))).thenAnswer(invocation -> {
            AnswerRecord record = invocation.getArgument(0);
            record.setAnswerId("answer_002");
            return record;
        });
        when(answerDataRepository.saveAll(anyList())).thenReturn(new ArrayList<>());
        when(reviewService.createReviewRequest(anyString())).thenReturn(new ReviewRecord());
        when(answerRecordRepository.findByAnswerId(anyString())).thenReturn(Optional.empty());
        doNothing().when(historyService).recordAnswerHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        AnswerSubmitResponse result = answerService.submitAnswer(request);

        assertNotNull(result);
        assertNotNull(result.getAnswerId());
        verify(reviewService, times(1)).createReviewRequest(anyString());
        verify(statisticsService, never()).updateStatistics(anyString());
    }

    @Test
    @DisplayName("测试答卷收集 - 问卷不存在时抛出异常")
    void testSubmitAnswer_SurveyNotFound() {
        String surveyId = "nonexistent";
        AnswerSubmitRequest request = TestDataBuilder.answerSubmitRequestBuilder()
                .surveyId(surveyId)
                .buildValidRequest(surveyId);

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.empty());

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            answerService.submitAnswer(request);
        });

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("问卷不存在"));
        verify(answerRecordRepository, never()).save(any(AnswerRecord.class));
    }

    @Test
    @DisplayName("测试答卷收集 - 问卷已关闭时拒绝提交")
    void testSubmitAnswer_SurveyClosed() {
        String surveyId = "survey_001";
        Survey closedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_CLOSED)
                .build();

        AnswerSubmitRequest request = TestDataBuilder.answerSubmitRequestBuilder()
                .buildValidRequest(surveyId);

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(closedSurvey));
        when(surveyService.isSurveyActive(surveyId)).thenReturn(false);

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            answerService.submitAnswer(request);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("问卷已关闭"));
    }

    @Test
    @DisplayName("测试答卷收集 - 问卷已过期时拒绝提交")
    void testSubmitAnswer_SurveyExpired() {
        String surveyId = "survey_001";
        Survey expiredSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .surveyDeadline(LocalDateTime.now().minusDays(1))
                .build();

        AnswerSubmitRequest request = TestDataBuilder.answerSubmitRequestBuilder()
                .buildValidRequest(surveyId);

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(expiredSurvey));
        when(surveyService.isSurveyActive(surveyId)).thenReturn(false);

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            answerService.submitAnswer(request);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("问卷已过期"));
    }

    @Test
    @DisplayName("测试答卷校验 - 必填题目未作答")
    void testValidateAnswers_RequiredQuestionNotAnswered() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .needReview(false)
                .build();

        Question requiredQuestion = TestDataBuilder.questionBuilder()
                .questionId("q_required")
                .questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                .required(true)
                .build();

        List<Question> questions = Arrays.asList(requiredQuestion);

        List<AnswerSubmitRequest.AnswerDataItem> emptyAnswers = new ArrayList<>();

        AnswerSubmitRequest request = new AnswerSubmitRequest(surveyId, "user_001", emptyAnswers);

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(publishedSurvey));
        when(surveyService.isSurveyActive(surveyId)).thenReturn(true);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            answerService.submitAnswer(request);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("答卷不完整"));
        verify(answerRecordRepository, never()).save(any(AnswerRecord.class));
    }

    @Test
    @DisplayName("测试答卷校验 - 单选答案选项无效")
    void testValidateAnswers_InvalidSingleChoiceOption() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .needReview(false)
                .build();

        Question singleQuestion = TestDataBuilder.questionBuilder()
                .questionId("q_single")
                .questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                .options(Arrays.asList("选项A", "选项B"))
                .required(true)
                .build();

        List<Question> questions = Arrays.asList(singleQuestion);

        List<AnswerSubmitRequest.AnswerDataItem> answerItems = Arrays.asList(
                new AnswerSubmitRequest.AnswerDataItem("q_single", "无效选项")
        );

        AnswerSubmitRequest request = new AnswerSubmitRequest(surveyId, "user_001", answerItems);

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(publishedSurvey));
        when(surveyService.isSurveyActive(surveyId)).thenReturn(true);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            answerService.submitAnswer(request);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("答案类型错误"));
    }

    @Test
    @DisplayName("测试答卷校验 - 评分题答案范围无效")
    void testValidateAnswers_InvalidRatingValue() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .needReview(false)
                .build();

        Question ratingQuestion = TestDataBuilder.questionBuilder()
                .questionId("q_rating")
                .questionType(SurveyConstants.QUESTION_TYPE_RATING)
                .required(true)
                .build();

        List<Question> questions = Arrays.asList(ratingQuestion);

        List<AnswerSubmitRequest.AnswerDataItem> answerItems = Arrays.asList(
                new AnswerSubmitRequest.AnswerDataItem("q_rating", "10")
        );

        AnswerSubmitRequest request = new AnswerSubmitRequest(surveyId, "user_001", answerItems);

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(publishedSurvey));
        when(surveyService.isSurveyActive(surveyId)).thenReturn(true);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            answerService.submitAnswer(request);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("答案类型错误"));
    }

    @Test
    @DisplayName("测试答卷校验 - 评分题非数字答案")
    void testValidateAnswers_NonNumericRating() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .needReview(false)
                .build();

        Question ratingQuestion = TestDataBuilder.questionBuilder()
                .questionId("q_rating")
                .questionType(SurveyConstants.QUESTION_TYPE_RATING)
                .required(true)
                .build();

        List<Question> questions = Arrays.asList(ratingQuestion);

        List<AnswerSubmitRequest.AnswerDataItem> answerItems = Arrays.asList(
                new AnswerSubmitRequest.AnswerDataItem("q_rating", "非常好")
        );

        AnswerSubmitRequest request = new AnswerSubmitRequest(surveyId, "user_001", answerItems);

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(publishedSurvey));
        when(surveyService.isSurveyActive(surveyId)).thenReturn(true);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            answerService.submitAnswer(request);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("答案类型错误"));
    }

    @Test
    @DisplayName("测试答卷数据存储 - 答案数据正确保存")
    void testAnswerDataStorage_CorrectSaving() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .needReview(false)
                .build();

        Question q1 = TestDataBuilder.questionBuilder()
                .questionId("q_001")
                .questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                .options(Arrays.asList("A", "B"))
                .required(true)
                .build();

        Question q2 = TestDataBuilder.questionBuilder()
                .questionId("q_002")
                .questionType(SurveyConstants.QUESTION_TYPE_TEXT)
                .required(false)
                .build();

        List<Question> questions = Arrays.asList(q1, q2);

        List<AnswerSubmitRequest.AnswerDataItem> answerItems = Arrays.asList(
                new AnswerSubmitRequest.AnswerDataItem("q_001", "A"),
                new AnswerSubmitRequest.AnswerDataItem("q_002", "这是我的建议")
        );

        AnswerSubmitRequest request = new AnswerSubmitRequest(surveyId, "user_001", answerItems);

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(publishedSurvey));
        when(surveyService.isSurveyActive(surveyId)).thenReturn(true);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerRecordRepository.save(any(AnswerRecord.class))).thenAnswer(invocation -> {
            AnswerRecord record = invocation.getArgument(0);
            record.setAnswerId("answer_003");
            return record;
        });
        when(answerDataRepository.saveAll(anyList())).thenReturn(new ArrayList<>());
        doNothing().when(statisticsService).updateStatistics(anyString());
        doNothing().when(historyService).recordAnswerHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        AnswerSubmitResponse result = answerService.submitAnswer(request);

        assertNotNull(result);
        verify(answerDataRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("测试答卷状态管理 - 初始状态为已提交")
    void testAnswerStatus_InitialStatus() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .needReview(false)
                .build();

        Question q1 = TestDataBuilder.questionBuilder()
                .questionId("q_001")
                .questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                .options(Arrays.asList("是", "否"))
                .required(true)
                .build();

        List<Question> questions = Arrays.asList(q1);

        List<AnswerSubmitRequest.AnswerDataItem> answerItems = Arrays.asList(
                new AnswerSubmitRequest.AnswerDataItem("q_001", "是")
        );

        AnswerSubmitRequest request = new AnswerSubmitRequest(surveyId, "user_001", answerItems);

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(publishedSurvey));
        when(surveyService.isSurveyActive(surveyId)).thenReturn(true);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerRecordRepository.save(any(AnswerRecord.class))).thenAnswer(invocation -> {
            AnswerRecord record = invocation.getArgument(0);
            record.setAnswerId("answer_004");
            return record;
        });
        when(answerDataRepository.saveAll(anyList())).thenReturn(new ArrayList<>());
        doNothing().when(statisticsService).updateStatistics(anyString());
        doNothing().when(historyService).recordAnswerHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        AnswerSubmitResponse result = answerService.submitAnswer(request);

        assertEquals(SurveyConstants.ANSWER_STATUS_SUBMITTED, result.getStatus());
    }

    @Test
    @DisplayName("测试答卷提醒机制 - 答卷提交后历史记录正确")
    void testAnswerReminder_HistoryRecord() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .needReview(false)
                .build();

        Question q1 = TestDataBuilder.questionBuilder()
                .questionId("q_001")
                .questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                .options(Arrays.asList("是", "否"))
                .required(true)
                .build();

        List<Question> questions = Arrays.asList(q1);

        List<AnswerSubmitRequest.AnswerDataItem> answerItems = Arrays.asList(
                new AnswerSubmitRequest.AnswerDataItem("q_001", "是")
        );

        AnswerSubmitRequest request = new AnswerSubmitRequest(surveyId, "user_001", answerItems);

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(publishedSurvey));
        when(surveyService.isSurveyActive(surveyId)).thenReturn(true);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerRecordRepository.save(any(AnswerRecord.class))).thenAnswer(invocation -> {
            AnswerRecord record = invocation.getArgument(0);
            record.setAnswerId("answer_005");
            return record;
        });
        when(answerDataRepository.saveAll(anyList())).thenReturn(new ArrayList<>());
        doNothing().when(statisticsService).updateStatistics(anyString());
        doNothing().when(historyService).recordAnswerHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        answerService.submitAnswer(request);

        verify(historyService, times(1)).recordAnswerHistory(anyString(), eq("SUBMIT_ANSWER"), anyString(), eq("user_001"));
        verify(historyService, times(1)).recordSurveyHistory(eq(surveyId), eq("ANSWER_SUBMITTED"), anyString(), any());
    }

    @Test
    @DisplayName("测试答卷查询 - 成功获取答卷")
    void testGetAnswer_Success() {
        String answerId = "answer_001";
        AnswerRecord record = TestDataBuilder.answerRecordBuilder()
                .answerId(answerId)
                .userId("user_001")
                .build();

        when(answerRecordRepository.findByAnswerId(answerId)).thenReturn(Optional.of(record));

        AnswerRecord result = answerService.getAnswer(answerId);

        assertNotNull(result);
        assertEquals(answerId, result.getAnswerId());
        assertEquals("user_001", result.getUserId());
    }

    @Test
    @DisplayName("测试答卷查询 - 答卷不存在时抛出异常")
    void testGetAnswer_NotFound() {
        String answerId = "nonexistent";

        when(answerRecordRepository.findByAnswerId(answerId)).thenReturn(Optional.empty());

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            answerService.getAnswer(answerId);
        });

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("答卷不存在"));
    }

    @Test
    @DisplayName("测试答卷查询 - 获取问卷的所有答卷")
    void testGetAnswersBySurvey_Success() {
        String surveyId = "survey_001";
        List<AnswerRecord> answers = new ArrayList<>();
        answers.add(TestDataBuilder.answerRecordBuilder().surveyId(surveyId).build());
        answers.add(TestDataBuilder.answerRecordBuilder().surveyId(surveyId).build());

        when(answerRecordRepository.findBySurveyId(surveyId)).thenReturn(answers);

        List<AnswerRecord> result = answerService.getAnswersBySurvey(surveyId);

        assertNotNull(result);
        assertEquals(2, result.size());
        result.forEach(a -> assertEquals(surveyId, a.getSurveyId()));
    }

    @Test
    @DisplayName("测试答卷查询 - 获取问卷指定状态的答卷")
    void testGetAnswersBySurveyAndStatus_Success() {
        String surveyId = "survey_001";
        String status = SurveyConstants.ANSWER_STATUS_REVIEWED;
        List<AnswerRecord> answers = new ArrayList<>();
        answers.add(TestDataBuilder.answerRecordBuilder().surveyId(surveyId).answerStatus(status).build());
        answers.add(TestDataBuilder.answerRecordBuilder().surveyId(surveyId).answerStatus(status).build());

        when(answerRecordRepository.findBySurveyIdAndAnswerStatus(surveyId, status)).thenReturn(answers);

        List<AnswerRecord> result = answerService.getAnswersBySurveyAndStatus(surveyId, status);

        assertNotNull(result);
        assertEquals(2, result.size());
        result.forEach(a -> assertEquals(status, a.getAnswerStatus()));
    }

    @Test
    @DisplayName("测试答卷计数 - 正确统计答卷数量")
    void testGetAnswerCount_Success() {
        String surveyId = "survey_001";
        long count = 100;

        when(answerRecordRepository.countBySurveyId(surveyId)).thenReturn(count);

        long result = answerService.getAnswerCount(surveyId);

        assertEquals(count, result);
    }

    @Test
    @DisplayName("测试答卷计数 - 正确统计指定状态的答卷数量")
    void testGetAnswerCountByStatus_Success() {
        String surveyId = "survey_001";
        String status = SurveyConstants.ANSWER_STATUS_REVIEWED;
        long count = 50;

        when(answerRecordRepository.countBySurveyIdAndAnswerStatus(surveyId, status)).thenReturn(count);

        long result = answerService.getAnswerCountByStatus(surveyId, status);

        assertEquals(count, result);
    }

    @Test
    @DisplayName("测试答卷详情查询 - 正确获取答案详情")
    void testGetAnswerDetails_Success() {
        String answerId = "answer_001";
        List<AnswerData> details = new ArrayList<>();
        details.add(new AnswerData());
        details.add(new AnswerData());

        when(answerDataRepository.findByAnswerRecord_AnswerId(answerId)).thenReturn(details);

        List<AnswerData> result = answerService.getAnswerDetails(answerId);

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("测试答卷状态更新 - 成功更新状态")
    void testUpdateAnswerStatus_Success() {
        String answerId = "answer_001";
        String newStatus = SurveyConstants.ANSWER_STATUS_REVIEWED;
        AnswerRecord record = TestDataBuilder.answerRecordBuilder()
                .answerId(answerId)
                .answerStatus(SurveyConstants.ANSWER_STATUS_SUBMITTED)
                .build();

        when(answerRecordRepository.findByAnswerId(answerId)).thenReturn(Optional.of(record));
        when(answerRecordRepository.save(any(AnswerRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        answerService.updateAnswerStatus(answerId, newStatus);

        verify(answerRecordRepository, times(1)).save(any(AnswerRecord.class));
    }

    @Test
    @DisplayName("测试答卷审核关联 - 成功设置审核ID")
    void testSetReviewId_Success() {
        String answerId = "answer_001";
        String reviewId = "review_001";
        AnswerRecord record = TestDataBuilder.answerRecordBuilder()
                .answerId(answerId)
                .build();

        when(answerRecordRepository.findByAnswerId(answerId)).thenReturn(Optional.of(record));
        when(answerRecordRepository.save(any(AnswerRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        answerService.setReviewId(answerId, reviewId);

        verify(answerRecordRepository, times(1)).save(any(AnswerRecord.class));
    }

    @Test
    @DisplayName("测试答卷不完整场景 - 空答案列表")
    void testSubmitAnswer_EmptyAnswerList() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .needReview(false)
                .build();

        Question requiredQuestion = TestDataBuilder.questionBuilder()
                .questionId("q_required")
                .questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                .required(true)
                .build();

        List<Question> questions = Arrays.asList(requiredQuestion);

        AnswerSubmitRequest request = new AnswerSubmitRequest(surveyId, "user_001", new ArrayList<>());

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(publishedSurvey));
        when(surveyService.isSurveyActive(surveyId)).thenReturn(true);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            answerService.submitAnswer(request);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("答卷不完整"));
    }

    @Test
    @DisplayName("测试答卷校验 - 非必填题目可以为空")
    void testValidateAnswers_OptionalQuestionCanBeEmpty() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .needReview(false)
                .build();

        Question requiredQuestion = TestDataBuilder.questionBuilder()
                .questionId("q_required")
                .questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                .options(Arrays.asList("是", "否"))
                .required(true)
                .build();

        Question optionalQuestion = TestDataBuilder.questionBuilder()
                .questionId("q_optional")
                .questionType(SurveyConstants.QUESTION_TYPE_TEXT)
                .required(false)
                .build();

        List<Question> questions = Arrays.asList(requiredQuestion, optionalQuestion);

        List<AnswerSubmitRequest.AnswerDataItem> answerItems = Arrays.asList(
                new AnswerSubmitRequest.AnswerDataItem("q_required", "是")
        );

        AnswerSubmitRequest request = new AnswerSubmitRequest(surveyId, "user_001", answerItems);

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(publishedSurvey));
        when(surveyService.isSurveyActive(surveyId)).thenReturn(true);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerRecordRepository.save(any(AnswerRecord.class))).thenAnswer(invocation -> {
            AnswerRecord record = invocation.getArgument(0);
            record.setAnswerId("answer_006");
            return record;
        });
        when(answerDataRepository.saveAll(anyList())).thenReturn(new ArrayList<>());
        doNothing().when(statisticsService).updateStatistics(anyString());
        doNothing().when(historyService).recordAnswerHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        AnswerSubmitResponse result = answerService.submitAnswer(request);

        assertNotNull(result);
        assertEquals(SurveyConstants.ANSWER_STATUS_SUBMITTED, result.getStatus());
    }
}
