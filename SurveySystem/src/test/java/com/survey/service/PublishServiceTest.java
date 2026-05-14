package com.survey.service;

import com.survey.builder.TestDataBuilder;
import com.survey.common.SurveyConstants;
import com.survey.dto.PublishRequest;
import com.survey.dto.PublishResponse;
import com.survey.entity.PublishRecord;
import com.survey.entity.Survey;
import com.survey.exception.SurveyException;
import com.survey.repository.PublishRecordRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("发布模块单元测试")
class PublishServiceTest {

    @Mock
    private PublishRecordRepository publishRecordRepository;

    @Mock
    private SurveyService surveyService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private PublishService publishService;

    @Test
    @DisplayName("测试问卷发布 - 成功发布草稿问卷")
    void testPublishSurvey_Success_DraftSurvey() {
        String surveyId = "survey_001";
        Survey draftSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyName("测试问卷")
                .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                .build();
        PublishRequest request = TestDataBuilder.publishRequestBuilder()
                .surveyId(surveyId)
                .publishChannel(SurveyConstants.PUBLISH_CHANNEL_EMAIL)
                .publishRange(SurveyConstants.PUBLISH_RANGE_ALL)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(draftSurvey));
        when(surveyService.isValidForPublish(surveyId)).thenReturn(true);
        when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
            PublishRecord record = invocation.getArgument(0);
            record.setPublishId(TestDataBuilder.generateId("publish"));
            return record;
        });
        doNothing().when(surveyService).updateSurveyStatus(anyString(), anyString());
        doNothing().when(historyService).recordPublishHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        PublishResponse result = publishService.publishSurvey(request);

        assertNotNull(result);
        assertNotNull(result.getPublishId());
        assertEquals(SurveyConstants.PUBLISH_STATUS_PUBLISHED, result.getStatus());
        assertNotNull(result.getPublishLink());
        verify(publishRecordRepository, times(1)).save(any(PublishRecord.class));
        verify(surveyService, times(1)).updateSurveyStatus(eq(surveyId), eq(SurveyConstants.SURVEY_STATUS_PUBLISHED));
        verify(historyService, times(1)).recordPublishHistory(anyString(), eq("PUBLISH_SURVEY"), anyString(), any());
        verify(historyService, times(1)).recordSurveyHistory(eq(surveyId), eq("PUBLISHED"), anyString(), any());
    }

    @Test
    @DisplayName("测试问卷发布 - 成功发布待发布问卷")
    void testPublishSurvey_Success_PendingSurvey() {
        String surveyId = "survey_001";
        Survey pendingSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyName("测试问卷")
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PENDING)
                .build();
        PublishRequest request = TestDataBuilder.publishRequestBuilder()
                .surveyId(surveyId)
                .publishChannel(SurveyConstants.PUBLISH_CHANNEL_LINK)
                .publishRange(SurveyConstants.PUBLISH_RANGE_TARGET)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(pendingSurvey));
        when(surveyService.isValidForPublish(surveyId)).thenReturn(true);
        when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
            PublishRecord record = invocation.getArgument(0);
            record.setPublishId(TestDataBuilder.generateId("publish"));
            return record;
        });
        doNothing().when(surveyService).updateSurveyStatus(anyString(), anyString());
        doNothing().when(historyService).recordPublishHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        PublishResponse result = publishService.publishSurvey(request);

        assertNotNull(result);
        assertNotNull(result.getPublishId());
        assertEquals(SurveyConstants.PUBLISH_STATUS_PUBLISHED, result.getStatus());
    }

    @Test
    @DisplayName("测试问卷发布 - 问卷不存在时抛出异常")
    void testPublishSurvey_SurveyNotFound() {
        String surveyId = "nonexistent";
        PublishRequest request = TestDataBuilder.publishRequestBuilder()
                .surveyId(surveyId)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.empty());

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            publishService.publishSurvey(request);
        });

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("问卷不存在"));
        verify(publishRecordRepository, never()).save(any(PublishRecord.class));
    }

    @Test
    @DisplayName("测试问卷发布 - 已关闭问卷不能发布")
    void testPublishSurvey_ClosedSurvey() {
        String surveyId = "survey_001";
        Survey closedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_CLOSED)
                .build();
        PublishRequest request = TestDataBuilder.publishRequestBuilder()
                .surveyId(surveyId)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(closedSurvey));

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            publishService.publishSurvey(request);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("问卷已关闭"));
        verify(publishRecordRepository, never()).save(any(PublishRecord.class));
    }

    @Test
    @DisplayName("测试问卷发布 - 已过期问卷不能发布")
    void testPublishSurvey_ExpiredSurvey() {
        String surveyId = "survey_001";
        Survey expiredSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_EXPIRED)
                .build();
        PublishRequest request = TestDataBuilder.publishRequestBuilder()
                .surveyId(surveyId)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(expiredSurvey));

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            publishService.publishSurvey(request);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("问卷已过期"));
        verify(publishRecordRepository, never()).save(any(PublishRecord.class));
    }

    @Test
    @DisplayName("测试问卷发布 - 无效状态问卷不能发布")
    void testPublishSurvey_InvalidStatus() {
        String surveyId = "survey_001";
        Survey publishedSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_PUBLISHED)
                .build();
        PublishRequest request = TestDataBuilder.publishRequestBuilder()
                .surveyId(surveyId)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(publishedSurvey));
        when(surveyService.isValidForPublish(surveyId)).thenReturn(false);

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            publishService.publishSurvey(request);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("问卷状态不允许发布"));
        verify(publishRecordRepository, never()).save(any(PublishRecord.class));
    }

    @Test
    @DisplayName("测试发布确认机制 - 邮件渠道发布确认")
    void testPublishConfirmation_EmailChannel() {
        String surveyId = "survey_001";
        Survey draftSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                .build();
        PublishRequest emailRequest = TestDataBuilder.publishRequestBuilder()
                .surveyId(surveyId)
                .publishChannel(SurveyConstants.PUBLISH_CHANNEL_EMAIL)
                .publishRange(SurveyConstants.PUBLISH_RANGE_ALL)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(draftSurvey));
        when(surveyService.isValidForPublish(surveyId)).thenReturn(true);
        when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
            PublishRecord record = invocation.getArgument(0);
            record.setPublishId("publish_email_001");
            return record;
        });
        doNothing().when(surveyService).updateSurveyStatus(anyString(), anyString());
        doNothing().when(historyService).recordPublishHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        PublishResponse result = publishService.publishSurvey(emailRequest);

        assertNotNull(result);
        assertEquals(SurveyConstants.PUBLISH_STATUS_PUBLISHED, result.getStatus());
        assertTrue(result.getPublishLink().startsWith("http://"));
    }

    @Test
    @DisplayName("测试发布渠道流转 - 链接渠道发布")
    void testPublishChannelTransition_LinkChannel() {
        String surveyId = "survey_001";
        Survey draftSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                .build();
        PublishRequest linkRequest = TestDataBuilder.publishRequestBuilder()
                .surveyId(surveyId)
                .publishChannel(SurveyConstants.PUBLISH_CHANNEL_LINK)
                .publishRange(SurveyConstants.PUBLISH_RANGE_TARGET)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(draftSurvey));
        when(surveyService.isValidForPublish(surveyId)).thenReturn(true);
        when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
            PublishRecord record = invocation.getArgument(0);
            record.setPublishId("publish_link_001");
            return record;
        });
        doNothing().when(surveyService).updateSurveyStatus(anyString(), anyString());
        doNothing().when(historyService).recordPublishHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        PublishResponse result = publishService.publishSurvey(linkRequest);

        assertNotNull(result);
        assertEquals(SurveyConstants.PUBLISH_STATUS_PUBLISHED, result.getStatus());
        assertNotNull(result.getPublishLink());
    }

    @Test
    @DisplayName("测试发布范围管理 - 全部用户范围")
    void testPublishRange_AllUsers() {
        String surveyId = "survey_001";
        Survey draftSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                .build();
        PublishRequest request = TestDataBuilder.publishRequestBuilder()
                .surveyId(surveyId)
                .publishRange(SurveyConstants.PUBLISH_RANGE_ALL)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(draftSurvey));
        when(surveyService.isValidForPublish(surveyId)).thenReturn(true);
        when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
            PublishRecord record = invocation.getArgument(0);
            return record;
        });
        doNothing().when(surveyService).updateSurveyStatus(anyString(), anyString());
        doNothing().when(historyService).recordPublishHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        PublishResponse result = publishService.publishSurvey(request);

        assertNotNull(result);
    }

    @Test
    @DisplayName("测试发布范围管理 - 目标用户范围")
    void testPublishRange_TargetUsers() {
        String surveyId = "survey_001";
        Survey draftSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                .build();
        PublishRequest request = TestDataBuilder.publishRequestBuilder()
                .surveyId(surveyId)
                .publishRange(SurveyConstants.PUBLISH_RANGE_TARGET)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(draftSurvey));
        when(surveyService.isValidForPublish(surveyId)).thenReturn(true);
        when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
            PublishRecord record = invocation.getArgument(0);
            return record;
        });
        doNothing().when(surveyService).updateSurveyStatus(anyString(), anyString());
        doNothing().when(historyService).recordPublishHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        PublishResponse result = publishService.publishSurvey(request);

        assertNotNull(result);
    }

    @Test
    @DisplayName("测试发布范围管理 - 部门范围")
    void testPublishRange_Department() {
        String surveyId = "survey_001";
        Survey draftSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                .build();
        PublishRequest request = TestDataBuilder.publishRequestBuilder()
                .surveyId(surveyId)
                .publishRange(SurveyConstants.PUBLISH_RANGE_DEPARTMENT)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(draftSurvey));
        when(surveyService.isValidForPublish(surveyId)).thenReturn(true);
        when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
            PublishRecord record = invocation.getArgument(0);
            return record;
        });
        doNothing().when(surveyService).updateSurveyStatus(anyString(), anyString());
        doNothing().when(historyService).recordPublishHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        PublishResponse result = publishService.publishSurvey(request);

        assertNotNull(result);
    }

    @Test
    @DisplayName("测试取消发布 - 成功取消发布")
    void testCancelPublish_Success() {
        String publishId = "publish_001";
        PublishRecord record = TestDataBuilder.publishRecordBuilder()
                .publishId(publishId)
                .publishStatus(SurveyConstants.PUBLISH_STATUS_PUBLISHED)
                .build();

        when(publishRecordRepository.findByPublishId(publishId)).thenReturn(Optional.of(record));
        when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordPublishHistory(anyString(), anyString(), anyString(), any());

        assertDoesNotThrow(() -> publishService.cancelPublish(publishId));

        verify(publishRecordRepository, times(1)).save(any(PublishRecord.class));
        verify(historyService, times(1)).recordPublishHistory(eq(publishId), eq("CANCEL_PUBLISH"), anyString(), any());
    }

    @Test
    @DisplayName("测试取消发布 - 发布记录不存在时抛出异常")
    void testCancelPublish_NotFound() {
        String publishId = "nonexistent";

        when(publishRecordRepository.findByPublishId(publishId)).thenReturn(Optional.empty());

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            publishService.cancelPublish(publishId);
        });

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("发布记录不存在"));
    }

    @Test
    @DisplayName("测试发布记录查询 - 成功获取发布记录")
    void testGetPublishRecord_Success() {
        String publishId = "publish_001";
        PublishRecord record = TestDataBuilder.publishRecordBuilder()
                .publishId(publishId)
                .publishChannel(SurveyConstants.PUBLISH_CHANNEL_EMAIL)
                .build();

        when(publishRecordRepository.findByPublishId(publishId)).thenReturn(Optional.of(record));

        PublishRecord result = publishService.getPublishRecord(publishId);

        assertNotNull(result);
        assertEquals(publishId, result.getPublishId());
        assertEquals(SurveyConstants.PUBLISH_CHANNEL_EMAIL, result.getPublishChannel());
    }

    @Test
    @DisplayName("测试发布记录查询 - 发布记录不存在时抛出异常")
    void testGetPublishRecord_NotFound() {
        String publishId = "nonexistent";

        when(publishRecordRepository.findByPublishId(publishId)).thenReturn(Optional.empty());

        SurveyException exception = assertThrows(SurveyException.class, () -> {
            publishService.getPublishRecord(publishId);
        });

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("发布记录不存在"));
    }

    @Test
    @DisplayName("测试发布记录查询 - 获取问卷的所有发布记录")
    void testGetPublishRecordsBySurvey_Success() {
        String surveyId = "survey_001";
        List<PublishRecord> records = new ArrayList<>();
        records.add(TestDataBuilder.publishRecordBuilder().surveyId(surveyId).build());
        records.add(TestDataBuilder.publishRecordBuilder().surveyId(surveyId).build());

        when(publishRecordRepository.findBySurveyId(surveyId)).thenReturn(records);

        List<PublishRecord> result = publishService.getPublishRecordsBySurvey(surveyId);

        assertNotNull(result);
        assertEquals(2, result.size());
        result.forEach(r -> assertEquals(surveyId, r.getSurveyId()));
    }

    @Test
    @DisplayName("测试发布记录查询 - 获取问卷的活跃发布记录")
    void testGetActivePublishRecords_Success() {
        String surveyId = "survey_001";
        List<PublishRecord> activeRecords = new ArrayList<>();
        activeRecords.add(TestDataBuilder.publishRecordBuilder()
                .surveyId(surveyId)
                .publishStatus(SurveyConstants.PUBLISH_STATUS_PUBLISHED)
                .build());

        when(publishRecordRepository.findBySurveyIdAndPublishStatus(surveyId, SurveyConstants.PUBLISH_STATUS_PUBLISHED))
                .thenReturn(activeRecords);

        List<PublishRecord> result = publishService.getActivePublishRecords(surveyId);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(SurveyConstants.PUBLISH_STATUS_PUBLISHED, result.get(0).getPublishStatus());
    }

    @Test
    @DisplayName("测试发布流程正确性 - 完整流程验证")
    void testPublishFlow_CompleteProcess() {
        String surveyId = "survey_001";
        Survey draftSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyName("完整流程测试问卷")
                .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                .build();
        PublishRequest request = TestDataBuilder.publishRequestBuilder()
                .surveyId(surveyId)
                .publishChannel(SurveyConstants.PUBLISH_CHANNEL_LINK)
                .publishRange(SurveyConstants.PUBLISH_RANGE_TARGET)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(draftSurvey));
        when(surveyService.isValidForPublish(surveyId)).thenReturn(true);
        when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
            PublishRecord record = invocation.getArgument(0);
            record.setPublishId("publish_complete_001");
            return record;
        });
        doNothing().when(surveyService).updateSurveyStatus(anyString(), anyString());
        doNothing().when(historyService).recordPublishHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        PublishResponse response = publishService.publishSurvey(request);

        assertNotNull(response);
        assertNotNull(response.getPublishId());
        assertEquals(SurveyConstants.PUBLISH_STATUS_PUBLISHED, response.getStatus());
        assertNotNull(response.getPublishLink());

        verify(publishRecordRepository, times(1)).save(any(PublishRecord.class));
        verify(surveyService, times(1)).updateSurveyStatus(surveyId, SurveyConstants.SURVEY_STATUS_PUBLISHED);
        verify(historyService, times(1)).recordPublishHistory(eq(response.getPublishId()), eq("PUBLISH_SURVEY"), anyString(), any());
        verify(historyService, times(1)).recordSurveyHistory(eq(surveyId), eq("PUBLISHED"), anyString(), any());
    }

    @Test
    @DisplayName("测试发布确认机制 - 生成的链接格式正确")
    void testPublishConfirmation_LinkFormat() {
        String surveyId = "survey_001";
        Survey draftSurvey = TestDataBuilder.surveyBuilder()
                .surveyId(surveyId)
                .surveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT)
                .build();
        PublishRequest request = TestDataBuilder.publishRequestBuilder()
                .surveyId(surveyId)
                .publishChannel(SurveyConstants.PUBLISH_CHANNEL_LINK)
                .build();

        when(surveyService.findSurvey(surveyId)).thenReturn(Optional.of(draftSurvey));
        when(surveyService.isValidForPublish(surveyId)).thenReturn(true);
        when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
            PublishRecord record = invocation.getArgument(0);
            record.setPublishId("publish_link_001");
            return record;
        });
        doNothing().when(surveyService).updateSurveyStatus(anyString(), anyString());
        doNothing().when(historyService).recordPublishHistory(anyString(), anyString(), anyString(), any());
        doNothing().when(historyService).recordSurveyHistory(anyString(), anyString(), anyString(), any());

        PublishResponse response = publishService.publishSurvey(request);

        assertNotNull(response.getPublishLink());
        assertTrue(response.getPublishLink().startsWith("http://localhost:8080/survey/"));
    }
}
