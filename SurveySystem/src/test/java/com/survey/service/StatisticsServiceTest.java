package com.survey.service;

import com.survey.builder.TestDataBuilder;
import com.survey.common.SurveyConstants;
import com.survey.dto.StatQueryResponse;
import com.survey.entity.*;
import com.survey.repository.AnswerDataRepository;
import com.survey.repository.StatRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("统计模块单元测试")
class StatisticsServiceTest {

    @Mock
    private StatRecordRepository statRecordRepository;

    @Mock
    private AnswerService answerService;

    @Mock
    private AnswerDataRepository answerDataRepository;

    @Mock
    private SurveyService surveyService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private StatisticsService statisticsService;

    @Test
    @DisplayName("测试统计更新 - 首次创建统计记录")
    void testUpdateStatistics_CreateNewRecord() {
        String surveyId = "survey_001";
        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .needReview(false)
                .build();

        List<Question> questions = new ArrayList<>();
        questions.add(TestDataBuilder.questionBuilder().questionId("q_001").build());

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(survey));
        when(statRecordRepository.findBySurveyId(surveyId)).thenReturn(Optional.empty());
        when(answerService.getAnswerCount(surveyId)).thenReturn(100L);
        when(answerService.getAnswerCountByStatus(eq(surveyId), anyString())).thenReturn(100L);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerDataRepository.findByQuestionId(anyString())).thenReturn(new ArrayList<>());
        when(statRecordRepository.save(any(StatRecord.class))).thenAnswer(invocation -> {
            StatRecord record = invocation.getArgument(0);
            record.setStatId("stat_new_001");
            return record;
        });
        doNothing().when(historyService).recordStatHistory(anyString(), anyString(), anyString(), any());

        StatRecord result = statisticsService.updateStatistics(surveyId);

        assertNotNull(result);
        assertEquals(100, result.getStatAnswerCount());
        assertEquals(100, result.getStatReviewedCount());
        assertEquals(1.0, result.getStatCompletionRate(), 0.001);
        verify(statRecordRepository, times(1)).save(any(StatRecord.class));
        verify(historyService, times(1)).recordStatHistory(anyString(), eq("UPDATE_STAT"), anyString(), any());
    }

    @Test
    @DisplayName("测试统计更新 - 更新已有统计记录")
    void testUpdateStatistics_UpdateExistingRecord() {
        String surveyId = "survey_001";
        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .needReview(false)
                .build();

        StatRecord existingRecord = TestDataBuilder.statRecordBuilder()
                .statId("stat_existing_001")
                .surveyId(surveyId)
                .statAnswerCount(50)
                .statReviewedCount(50)
                .statCompletionRate(1.0)
                .build();

        List<Question> questions = new ArrayList<>();
        questions.add(TestDataBuilder.questionBuilder().questionId("q_001").build());

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(survey));
        when(statRecordRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(existingRecord));
        when(answerService.getAnswerCount(surveyId)).thenReturn(200L);
        when(answerService.getAnswerCountByStatus(eq(surveyId), anyString())).thenReturn(200L);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerDataRepository.findByQuestionId(anyString())).thenReturn(new ArrayList<>());
        when(statRecordRepository.save(any(StatRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordStatHistory(anyString(), anyString(), anyString(), any());

        StatRecord result = statisticsService.updateStatistics(surveyId);

        assertNotNull(result);
        assertEquals(200, result.getStatAnswerCount());
        assertEquals(200, result.getStatReviewedCount());
        verify(statRecordRepository, times(1)).save(existingRecord);
    }

    @Test
    @DisplayName("测试统计更新 - 带审核流程的统计")
    void testUpdateStatistics_WithReview() {
        String surveyId = "survey_001";
        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .needReview(true)
                .build();

        StatRecord existingRecord = TestDataBuilder.statRecordBuilder()
                .surveyId(surveyId)
                .build();

        List<Question> questions = new ArrayList<>();
        questions.add(TestDataBuilder.questionBuilder().questionId("q_001").build());

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(survey));
        when(statRecordRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(existingRecord));
        when(answerService.getAnswerCount(surveyId)).thenReturn(100L);
        when(answerService.getAnswerCountByStatus(surveyId, SurveyConstants.ANSWER_STATUS_REVIEWED)).thenReturn(80L);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerDataRepository.findByQuestionId(anyString())).thenReturn(new ArrayList<>());
        when(statRecordRepository.save(any(StatRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordStatHistory(anyString(), anyString(), anyString(), any());

        StatRecord result = statisticsService.updateStatistics(surveyId);

        assertEquals(100, result.getStatAnswerCount());
        assertEquals(80, result.getStatReviewedCount());
        assertEquals(0.8, result.getStatCompletionRate(), 0.001);
    }

    @Test
    @DisplayName("测试统计计算 - 无答卷时的统计")
    void testUpdateStatistics_NoAnswers() {
        String surveyId = "survey_001";
        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .needReview(false)
                .build();

        StatRecord existingRecord = TestDataBuilder.statRecordBuilder()
                .surveyId(surveyId)
                .build();

        List<Question> questions = new ArrayList<>();
        questions.add(TestDataBuilder.questionBuilder().questionId("q_001").build());

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(survey));
        when(statRecordRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(existingRecord));
        when(answerService.getAnswerCount(surveyId)).thenReturn(0L);
        when(answerService.getAnswerCountByStatus(eq(surveyId), anyString())).thenReturn(0L);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerDataRepository.findByQuestionId(anyString())).thenReturn(new ArrayList<>());
        when(statRecordRepository.save(any(StatRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordStatHistory(anyString(), anyString(), anyString(), any());

        StatRecord result = statisticsService.updateStatistics(surveyId);

        assertEquals(0, result.getStatAnswerCount());
        assertEquals(0, result.getStatReviewedCount());
        assertEquals(0.0, result.getStatCompletionRate(), 0.001);
    }

    @Test
    @DisplayName("测试统计计算 - 部分审核的统计")
    void testUpdateStatistics_PartialReview() {
        String surveyId = "survey_001";
        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .needReview(true)
                .build();

        StatRecord existingRecord = TestDataBuilder.statRecordBuilder()
                .surveyId(surveyId)
                .build();

        List<Question> questions = new ArrayList<>();
        questions.add(TestDataBuilder.questionBuilder().questionId("q_001").build());

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(survey));
        when(statRecordRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(existingRecord));
        when(answerService.getAnswerCount(surveyId)).thenReturn(100L);
        when(answerService.getAnswerCountByStatus(surveyId, SurveyConstants.ANSWER_STATUS_REVIEWED)).thenReturn(50L);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerDataRepository.findByQuestionId(anyString())).thenReturn(new ArrayList<>());
        when(statRecordRepository.save(any(StatRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordStatHistory(anyString(), anyString(), anyString(), any());

        StatRecord result = statisticsService.updateStatistics(surveyId);

        assertEquals(100, result.getStatAnswerCount());
        assertEquals(50, result.getStatReviewedCount());
        assertEquals(0.5, result.getStatCompletionRate(), 0.001);
    }

    @Test
    @DisplayName("测试统计计算 - 完成率不超过100%")
    void testUpdateStatistics_CompletionRateNotExceed100() {
        String surveyId = "survey_001";
        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .needReview(true)
                .build();

        StatRecord existingRecord = TestDataBuilder.statRecordBuilder()
                .surveyId(surveyId)
                .build();

        List<Question> questions = new ArrayList<>();
        questions.add(TestDataBuilder.questionBuilder().questionId("q_001").build());

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(survey));
        when(statRecordRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(existingRecord));
        when(answerService.getAnswerCount(surveyId)).thenReturn(50L);
        when(answerService.getAnswerCountByStatus(surveyId, SurveyConstants.ANSWER_STATUS_REVIEWED)).thenReturn(100L);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerDataRepository.findByQuestionId(anyString())).thenReturn(new ArrayList<>());
        when(statRecordRepository.save(any(StatRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordStatHistory(anyString(), anyString(), anyString(), any());

        StatRecord result = statisticsService.updateStatistics(surveyId);

        assertTrue(result.getStatCompletionRate() <= 1.0);
    }

    @Test
    @DisplayName("测试统计查询 - 已有统计记录时直接返回")
    void testGetStatistics_ExistingRecord() {
        String surveyId = "survey_001";
        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .needReview(false)
                .build();

        StatRecord existingRecord = TestDataBuilder.statRecordBuilder()
                .statId("stat_001")
                .surveyId(surveyId)
                .statAnswerCount(100)
                .statReviewedCount(100)
                .statCompletionRate(1.0)
                .statQuestionStat("{}")
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(survey));
        when(statRecordRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(existingRecord));

        StatQueryResponse result = statisticsService.getStatistics(surveyId);

        assertNotNull(result);
        assertEquals(100, result.getAnswerCount());
        assertEquals(100, result.getReviewedCount());
        assertEquals(1.0, result.getCompletionRate(), 0.001);
        assertEquals("{}", result.getQuestionStat());
        verify(statRecordRepository, never()).save(any(StatRecord.class));
    }

    @Test
    @DisplayName("测试统计查询 - 无统计记录时触发更新")
    void testGetStatistics_TriggerUpdate() {
        String surveyId = "survey_001";
        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .needReview(false)
                .build();

        List<Question> questions = new ArrayList<>();
        questions.add(TestDataBuilder.questionBuilder().questionId("q_001").build());

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(survey));
        when(statRecordRepository.findBySurveyId(surveyId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(TestDataBuilder.statRecordBuilder()
                        .statId("stat_new_002")
                        .statAnswerCount(50)
                        .statReviewedCount(50)
                        .statCompletionRate(1.0)
                        .build()));
        when(answerService.getAnswerCount(surveyId)).thenReturn(50L);
        when(answerService.getAnswerCountByStatus(eq(surveyId), anyString())).thenReturn(50L);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerDataRepository.findByQuestionId(anyString())).thenReturn(new ArrayList<>());
        when(statRecordRepository.save(any(StatRecord.class))).thenAnswer(invocation -> {
            StatRecord record = invocation.getArgument(0);
            record.setStatId("stat_new_002");
            return record;
        });
        doNothing().when(historyService).recordStatHistory(anyString(), anyString(), anyString(), any());

        StatQueryResponse result = statisticsService.getStatistics(surveyId);

        assertNotNull(result);
        assertEquals(50, result.getAnswerCount());
        verify(statRecordRepository, times(1)).save(any(StatRecord.class));
    }

    @Test
    @DisplayName("测试统计查询 - 问卷不存在时抛出异常")
    void testGetStatistics_SurveyNotFound() {
        String surveyId = "nonexistent";

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.empty());

        com.survey.exception.SurveyException exception = assertThrows(
                com.survey.exception.SurveyException.class,
                () -> statisticsService.getStatistics(surveyId)
        );

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("问卷不存在"));
    }

    @Test
    @DisplayName("测试题目统计 - 计算单选答案分布")
    void testCalculateQuestionStatistics_SingleChoice() {
        String surveyId = "survey_001";
        String questionId = "q_single";

        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .needReview(false)
                .build();

        Question singleQuestion = TestDataBuilder.questionBuilder()
                .questionId(questionId)
                .questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                .options(Arrays.asList("选项A", "选项B", "选项C"))
                .build();

        List<Question> questions = Arrays.asList(singleQuestion);

        List<AnswerData> answerDataList = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AnswerData data = new AnswerData();
            data.setQuestionId(questionId);
            data.setAnswerValue("选项A");
            answerDataList.add(data);
        }
        for (int i = 0; i < 3; i++) {
            AnswerData data = new AnswerData();
            data.setQuestionId(questionId);
            data.setAnswerValue("选项B");
            answerDataList.add(data);
        }

        StatRecord existingRecord = TestDataBuilder.statRecordBuilder()
                .surveyId(surveyId)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(survey));
        when(statRecordRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(existingRecord));
        when(answerService.getAnswerCount(surveyId)).thenReturn(8L);
        when(answerService.getAnswerCountByStatus(eq(surveyId), anyString())).thenReturn(8L);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerDataRepository.findByQuestionId(questionId)).thenReturn(answerDataList);
        when(statRecordRepository.save(any(StatRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordStatHistory(anyString(), anyString(), anyString(), any());

        StatRecord result = statisticsService.updateStatistics(surveyId);

        assertNotNull(result);
        assertNotNull(result.getStatQuestionStat());
        assertTrue(result.getStatQuestionStat().contains("选项A"));
        assertTrue(result.getStatQuestionStat().contains("选项B"));
    }

    @Test
    @DisplayName("测试异步统计处理 - 并发更新统计")
    void testAsyncStatistics_ConcurrentUpdate() throws InterruptedException {
        String surveyId = "survey_001";
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .needReview(false)
                .build();

        StatRecord existingRecord = TestDataBuilder.statRecordBuilder()
                .surveyId(surveyId)
                .statAnswerCount(0)
                .build();

        List<Question> questions = new ArrayList<>();
        questions.add(TestDataBuilder.questionBuilder().questionId("q_001").build());

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(survey));
        when(statRecordRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(existingRecord));
        when(answerService.getAnswerCount(surveyId)).thenReturn(100L);
        when(answerService.getAnswerCountByStatus(eq(surveyId), anyString())).thenReturn(100L);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerDataRepository.findByQuestionId(anyString())).thenReturn(new ArrayList<>());
        when(statRecordRepository.save(any(StatRecord.class))).thenAnswer(invocation -> {
            successCount.incrementAndGet();
            return invocation.getArgument(0);
        });
        doNothing().when(historyService).recordStatHistory(anyString(), anyString(), anyString(), any());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    statisticsService.updateStatistics(surveyId);
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertTrue(successCount.get() > 0);
    }

    @Test
    @DisplayName("测试统计记录查询 - 统计JSON解析")
    void testParseQuestionStatistics_ValidJson() {
        String validJson = "{\"q_001\":{\"A\":10,\"B\":20},\"q_002\":{\"1\":5,\"2\":15}}";

        java.util.Map<String, java.util.Map<String, Integer>> result = statisticsService.parseQuestionStatistics(validJson);

        assertNotNull(result);
        assertTrue(result.containsKey("q_001"));
        assertTrue(result.containsKey("q_002"));
    }

    @Test
    @DisplayName("测试统计记录查询 - 统计JSON解析错误返回空")
    void testParseQuestionStatistics_InvalidJson() {
        String invalidJson = "invalid json {{{";

        java.util.Map<String, java.util.Map<String, Integer>> result = statisticsService.parseQuestionStatistics(invalidJson);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("测试统计计算 - 完整流程验证")
    void testUpdateStatistics_CompleteFlow() {
        String surveyId = "survey_complete_001";
        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyName("完整流程统计测试")
                .needReview(true)
                .build();

        Question q1 = TestDataBuilder.questionBuilder()
                .questionId("q_1")
                .questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                .options(Arrays.asList("满意", "一般", "不满意"))
                .build();

        Question q2 = TestDataBuilder.questionBuilder()
                .questionId("q_2")
                .questionType(SurveyConstants.QUESTION_TYPE_RATING)
                .build();

        List<Question> questions = Arrays.asList(q1, q2);

        List<AnswerData> q1Answers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            AnswerData data = new AnswerData();
            data.setQuestionId("q_1");
            data.setAnswerValue("满意");
            q1Answers.add(data);
        }
        for (int i = 0; i < 3; i++) {
            AnswerData data = new AnswerData();
            data.setQuestionId("q_1");
            data.setAnswerValue("一般");
            q1Answers.add(data);
        }

        List<AnswerData> q2Answers = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            AnswerData data = new AnswerData();
            data.setQuestionId("q_2");
            data.setAnswerValue(String.valueOf(i % 5 + 1));
            q2Answers.add(data);
        }

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(survey));
        when(statRecordRepository.findBySurveyId(surveyId)).thenReturn(Optional.empty());
        when(answerService.getAnswerCount(surveyId)).thenReturn(10L);
        when(answerService.getAnswerCountByStatus(surveyId, SurveyConstants.ANSWER_STATUS_REVIEWED)).thenReturn(9L);
        when(surveyService.getSurveyQuestions(surveyId)).thenReturn(questions);
        when(answerDataRepository.findByQuestionId("q_1")).thenReturn(q1Answers);
        when(answerDataRepository.findByQuestionId("q_2")).thenReturn(q2Answers);
        when(statRecordRepository.save(any(StatRecord.class))).thenAnswer(invocation -> {
            StatRecord record = invocation.getArgument(0);
            record.setStatId("stat_complete_001");
            return record;
        });
        doNothing().when(historyService).recordStatHistory(anyString(), anyString(), anyString(), any());

        StatRecord result = statisticsService.updateStatistics(surveyId);

        assertNotNull(result);
        assertEquals(10, result.getStatAnswerCount());
        assertEquals(9, result.getStatReviewedCount());
        assertEquals(0.9, result.getStatCompletionRate(), 0.001);
        assertNotNull(result.getStatQuestionStat());
        verify(statRecordRepository, times(1)).save(any(StatRecord.class));
        verify(historyService, times(1)).recordStatHistory(eq(result.getStatId()), eq("UPDATE_STAT"), anyString(), any());
    }

    @Test
    @DisplayName("测试统计查询 - 响应格式验证")
    void testGetStatistics_ResponseFormat() {
        String surveyId = "survey_001";
        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .needReview(false)
                .build();

        StatRecord existingRecord = TestDataBuilder.statRecordBuilder()
                .statId("stat_format_001")
                .surveyId(surveyId)
                .statAnswerCount(200)
                .statReviewedCount(180)
                .statCompletionRate(0.9)
                .statQuestionStat("{\"stats\":\"test\"}")
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(survey));
        when(statRecordRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(existingRecord));

        StatQueryResponse result = statisticsService.getStatistics(surveyId);

        assertNotNull(result);
        assertNotNull(result.getAnswerCount());
        assertNotNull(result.getReviewedCount());
        assertNotNull(result.getCompletionRate());
        assertNotNull(result.getQuestionStat());
        assertEquals(200, result.getAnswerCount());
        assertEquals(180, result.getReviewedCount());
        assertEquals(0.9, result.getCompletionRate(), 0.001);
    }
}
