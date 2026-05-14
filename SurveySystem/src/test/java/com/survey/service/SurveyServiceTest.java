package com.survey.service;

import com.survey.builder.TestDataBuilder;
import com.survey.common.SurveyConstants;
import com.survey.dto.SurveyCreateRequest;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.exception.SurveyException;
import com.survey.repository.QuestionRepository;
import com.survey.repository.SurveyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("问卷管理模块单元测试")
class SurveyServiceTest {

    @Mock
    private SurveyRepository surveyRepository;

    @Mock
    private QuestionRepository questionRepository;

    @Mock
    private SurveyTypeService surveyTypeService;

    @Mock
    private SurveyTemplateService templateService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private SurveyService surveyService;

    @Test
    @DisplayName("测试问卷创建 - 成功创建问卷")
    void testCreateSurvey_Success() {
        SurveyCreateRequest request = TestDataBuilder.surveyCreateRequestBuilder().buildValidRequest();

        when(surveyTypeService.typeExists("satisfaction")).thenReturn(true);
        when(templateService.templateExists(anyString())).thenReturn(false);
        when(surveyRepository.save(any(Survey.class))).thenAnswer(invocation -> {
            Survey s = invocation.getArgument(0);
            s.setSurveyId(TestDataBuilder.generateId("survey"));
            return s;
        });
        when(questionRepository.saveAll(anyList())).thenReturn(new ArrayList<>());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        Survey result = surveyService.createSurvey(request);

        assertNotNull(result);
        assertNotNull(result.getSurveyId());
        assertEquals(request.getSurveyName(), result.getSurveyName());
        assertEquals(request.getSurveyType(), result.getSurveyType());
        assertEquals(SurveyConstants.SURVEY_STATUS_DRAFT, result.getSurveyStatus());
        verify(surveyRepository, times(1)).save(any(Survey.class));
        verify(questionRepository, times(1)).saveAll(anyList());
        verify(historyService, times(1)).recordSurveyHistory(anyString(), eq("CREATE_SURVEY"), anyString(), any());
    }

    @Test
    @DisplayName("测试问卷创建 - 问卷类型不存在时抛出异常")
    void testCreateSurvey_InvalidType() {
        SurveyCreateRequest request = TestDataBuilder.surveyCreateRequestBuilder().buildValidRequest();

        when(surveyTypeService.typeExists(request.getSurveyType())).thenReturn(false);

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            surveyService.createSurvey(request);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("问卷类型不存在"));
        verify(surveyRepository, never()).save(any(Survey.class));
    }

    @Test
    @DisplayName("测试问卷创建 - 模板不存在时抛出异常")
    void testCreateSurvey_InvalidTemplate() {
        SurveyCreateRequest request = TestDataBuilder.surveyCreateRequestBuilder()
                .buildValidRequest()
                .setTemplateId("template_001");

        when(surveyTypeService.typeExists(request.getSurveyType())).thenReturn(true);
        when(templateService.templateExists("template_001")).thenReturn(false);

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            surveyService.createSurvey(request);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("模板不存在"));
        verify(surveyRepository, never()).save(any(Survey.class));
    }

    @Test
    @DisplayName("测试问卷更新 - 成功更新草稿状态的问卷")
    void testUpdateSurvey_Success() {
        String surveyId = "survey_001";
        Survey existingSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                .build();
        SurveyCreateRequest updateRequest = TestDataBuilder.surveyCreateRequestBuilder()
                .surveyName("更新后的问卷")
                .buildValidRequest();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(existingSurvey));
        when(surveyRepository.save(any(Survey.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(questionRepository.saveAll(anyList())).thenReturn(new ArrayList<>());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        Survey result = surveyService.updateSurvey(surveyId, updateRequest);

        assertNotNull(result);
        assertEquals("更新后的问卷", result.getSurveyName());
        verify(questionRepository, times(1)).deleteAll(anyList());
        verify(questionRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("测试问卷更新 - 非草稿状态问卷不能更新")
    void testUpdateSurvey_InvalidStatus() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .build();
        SurveyCreateRequest updateRequest = TestDataBuilder.surveyCreateRequestBuilder().buildValidRequest();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(publishedSurvey));

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            surveyService.updateSurvey(surveyId, updateRequest);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("只能修改草稿状态的问卷"));
        verify(surveyRepository, never()).save(any(Survey.class));
    }

    @Test
    @DisplayName("测试问卷更新 - 问卷不存在时抛出异常")
    void testUpdateSurvey_NotFound() {
        String surveyId = "nonexistent";
        SurveyCreateRequest updateRequest = TestDataBuilder.surveyCreateRequestBuilder().buildValidRequest();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.empty());

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            surveyService.updateSurvey(surveyId, updateRequest);
        });

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("问卷不存在"));
    }

    @Test
    @DisplayName("测试问卷删除 - 成功删除草稿问卷")
    void testDeleteSurvey_Success() {
        String surveyId = "survey_001";
        Survey draftSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                .build();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(draftSurvey));
        doNothing().when(surveyRepository).delete(any(Survey.class));
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        assertDoesNotThrow(() -> surveyService.deleteSurvey(surveyId));

        verify(surveyRepository, times(1)).delete(draftSurvey);
        verify(historyService, times(1)).recordSurveyHistory(eq(surveyId), eq("DELETE_SURVEY"), anyString(), any());
    }

    @Test
    @DisplayName("测试问卷删除 - 已发布的问卷不能删除")
    void testDeleteSurvey_PublishedCannotDelete() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .build();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(publishedSurvey));

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            surveyService.deleteSurvey(surveyId);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("已发布的问卷不能删除"));
        verify(surveyRepository, never()).delete(any(Survey.class));
    }

    @Test
    @DisplayName("测试问卷提交待发布 - 草稿状态问卷可以提交")
    void testSubmitForReview_Success() {
        String surveyId = "survey_001";
        Survey draftSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                .build();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(draftSurvey));
        when(surveyRepository.save(any(Survey.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        Survey result = surveyService.submitForReview(surveyId);

        assertEquals(SurveyConstants.SURVEY_STATUS_PENDING, result.getSurveyStatus());
        verify(historyService, times(1)).recordSurveyHistory(eq(surveyId), eq("SUBMIT_FOR_PUBLISH"), anyString(), any());
    }

    @Test
    @DisplayName("测试问卷提交待发布 - 非草稿状态问卷不能提交")
    void testSubmitForReview_InvalidStatus() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .build();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(publishedSurvey));

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            surveyService.submitForReview(surveyId);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("只有草稿状态的问卷可以提交"));
    }

    @Test
    @DisplayName("测试问卷状态管理 - 更新状态为已发布")
    void testUpdateSurveyStatus_ToPublished() {
        String surveyId = "survey_001";
        Survey draftSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                .build();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(draftSurvey));
        when(surveyRepository.save(any(Survey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        surveyService.updateSurveyStatus(surveyId, SurveyConstants.SURVEY_STATUS_PUBLISHED);

        verify(surveyRepository, times(1)).save(any(Survey.class));
    }

    @Test
    @DisplayName("测试问卷状态管理 - 问卷不存在时抛出异常")
    void testUpdateSurveyStatus_NotFound() {
        String surveyId = "nonexistent";

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.empty());

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            surveyService.updateSurveyStatus(surveyId, SurveyConstants.SURVEY_STATUS_PUBLISHED);
        });

        assertEquals(404, exception.getCode());
    }

    @Test
    @DisplayName("测试问卷查询 - 成功获取问卷")
    void testGetSurvey_Success() {
        String surveyId = "survey_001";
        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyName("测试问卷")
                .build();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(survey));

        Survey result = surveyService.getSurvey(surveyId);

        assertNotNull(result);
        assertEquals(surveyId, result.getSurveyId());
        assertEquals("测试问卷", result.getSurveyName());
    }

    @Test
    @DisplayName("测试问卷查询 - 问卷不存在时抛出异常")
    void testGetSurvey_NotFound() {
        String surveyId = "nonexistent";

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.empty());

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            surveyService.getSurvey(surveyId);
        });

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("问卷不存在"));
    }

    @Test
    @DisplayName("测试问卷查询 - 可选查询")
    void testFindSurvey_Existing() {
        String surveyId = "survey_001";
        Survey survey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .build();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(survey));

        Optional<Survey> result = surveyService.findSurvey(surveyId);

        assertTrue(result.isPresent());
        assertEquals(surveyId, result.get().getSurveyId());
    }

    @Test
    @DisplayName("测试问卷查询 - 可选查询空")
    void testFindSurvey_NonExisting() {
        String surveyId = "nonexistent";

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.empty());

        Optional<Survey> result = surveyService.findSurvey(surveyId);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("测试问卷题目配置 - 获取问卷题目")
    void testGetSurveyQuestions_Success() {
        String surveyId = "survey_001";
        List<Question> questions = new ArrayList<>();
        questions.add(TestDataBuilder.questionBuilder()
                .questionId("q_001")
                .questionType(SurveyConstants.QUESTION_TYPE_SINGLE)
                .questionContent("问题1")
                .build());
        questions.add(TestDataBuilder.questionBuilder()
                .questionId("q_002")
                .questionType(SurveyConstants.QUESTION_TYPE_TEXT)
                .questionContent("问题2")
                .build());

        when(questionRepository.findBySurvey_SurveyIdOrderByQuestionOrderAsc(surveyId)).thenReturn(questions);

        List<Question> result = surveyService.getSurveyQuestions(surveyId);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("q_001", result.get(0).getQuestionId());
        assertEquals("q_002", result.get(1).getQuestionId());
    }

    @Test
    @DisplayName("测试问卷发布有效性 - 草稿状态可发布")
    void testIsValidForPublish_DraftStatus() {
        String surveyId = "survey_001";
        Survey draftSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                .build();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(draftSurvey));

        boolean result = surveyService.isValidForPublish(surveyId);

        assertTrue(result);
    }

    @Test
    @DisplayName("测试问卷发布有效性 - 待发布状态可发布")
    void testIsValidForPublish_PendingStatus() {
        String surveyId = "survey_001";
        Survey pendingSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PENDING)
                .build();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(pendingSurvey));

        boolean result = surveyService.isValidForPublish(surveyId);

        assertTrue(result);
    }

    @Test
    @DisplayName("测试问卷发布有效性 - 已发布状态不可发布")
    void testIsValidForPublish_PublishedStatus() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .build();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(publishedSurvey));

        boolean result = surveyService.isValidForPublish(surveyId);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试问卷活跃度 - 已发布且未过期")
    void testIsSurveyActive_PublishedAndNotExpired() {
        String surveyId = "survey_001";
        Survey activeSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .surveyDeadline(java.time.LocalDateTime.now().plusDays(7))
                .build();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(activeSurvey));

        boolean result = surveyService.isSurveyActive(surveyId);

        assertTrue(result);
    }

    @Test
    @DisplayName("测试问卷活跃度 - 已关闭")
    void testIsSurveyActive_Closed() {
        String surveyId = "survey_001";
        Survey closedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_CLOSED)
                .build();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(closedSurvey));

        boolean result = surveyService.isSurveyActive(surveyId);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试问卷活跃度 - 已过期")
    void testIsSurveyActive_Expired() {
        String surveyId = "survey_001";
        Survey expiredSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .surveyDeadline(java.time.LocalDateTime.now().minusDays(1))
                .build();

        when(surveyRepository.findBySurveyId(surveyId)).thenReturn(Optional.of(expiredSurvey));

        boolean result = surveyService.isSurveyActive(surveyId);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试问卷创建 - 问卷配置管理完整性")
    void testCreateSurvey_ConfigurationComplete() {
        SurveyCreateRequest request = TestDataBuilder.surveyCreateRequestBuilder().buildValidRequest();
        request.setNeedReview(true);

        when(surveyTypeService.typeExists(request.getSurveyType())).thenReturn(true);
        when(templateService.templateExists(anyString())).thenReturn(false);
        when(surveyRepository.save(any(Survey.class))).thenAnswer(invocation -> {
            Survey s = invocation.getArgument(0);
            s.setSurveyId(TestDataBuilder.generateId("survey"));
            return s;
        });
        when(questionRepository.saveAll(anyList())).thenReturn(new ArrayList<>());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        Survey result = surveyService.createSurvey(request);

        assertNotNull(result);
        assertTrue(result.getNeedReview());
        assertEquals(request.getSurveyDescription(), result.getSurveyDescription());
        assertNotNull(result.getCreatedAt());
        assertNull(result.getPublishedAt());
    }

    @Test
    @DisplayName("测试问卷按状态查询 - 获取指定状态的问卷")
    void testGetSurveysByStatus_Success() {
        String status = SurveyConstants.SURVEY_STATUS_DRAFT;
        List<Survey> surveys = new ArrayList<>();
        surveys.add(TestDataBuilder.surveyBuilder().surveyStatus(status).build());
        surveys.add(TestDataBuilder.surveyBuilder().surveyStatus(status).build());

        when(surveyRepository.findBySurveyStatus(status)).thenReturn(surveys);

        List<Survey> result = surveyService.getSurveysByStatus(status);

        assertNotNull(result);
        assertEquals(2, result.size());
        result.forEach(s -> assertEquals(status, s.getSurveyStatus()));
    }

    @Test
    @DisplayName("测试问卷按类型查询 - 获取指定类型的问卷")
    void testGetSurveysByType_Success() {
        String type = "satisfaction";
        List<Survey> surveys = new ArrayList<>();
        surveys.add(TestDataBuilder.surveyBuilder().surveyType(type).build());
        surveys.add(TestDataBuilder.surveyBuilder().surveyType(type).build());

        when(surveyRepository.findBySurveyType(type)).thenReturn(surveys);

        List<Survey> result = surveyService.getSurveysByType(type);

        assertNotNull(result);
        assertEquals(2, result.size());
        result.forEach(s -> assertEquals(type, s.getSurveyType()));
    }

    @Test
    @DisplayName("测试问卷创建 - 题目配置正确性")
    void testCreateSurvey_QuestionConfiguration() {
        SurveyCreateRequest request = TestDataBuilder.surveyCreateRequestBuilder().buildValidRequest();

        when(surveyTypeService.typeExists(request.getSurveyType())).thenReturn(true);
        when(templateService.templateExists(anyString())).thenReturn(false);
        when(surveyRepository.save(any(Survey.class))).thenAnswer(invocation -> {
            Survey s = invocation.getArgument(0);
            s.setSurveyId(TestDataBuilder.generateId("survey"));
            return s;
        });
        when(questionRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<Question> saved = invocation.getArgument(0);
            return saved;
        });
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        Survey result = surveyService.createSurvey(request);

        assertNotNull(result);
        verify(questionRepository, times(1)).saveAll(anyList());
    }
}
