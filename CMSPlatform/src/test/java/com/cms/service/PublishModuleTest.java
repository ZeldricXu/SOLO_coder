package com.cms.service;

import com.cms.builder.TestDataBuilder;
import com.cms.dto.PublishExecuteRequest;
import com.cms.entity.Content;
import com.cms.entity.PublishRecord;
import com.cms.exception.BusinessException;
import com.cms.repository.HistoryRecordRepository;
import com.cms.repository.PublishRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("发布模块测试")
class PublishModuleTest {

    @Mock
    private PublishRecordRepository publishRecordRepository;

    @Mock
    private ContentService contentService;

    @Mock
    private HistoryRecordRepository historyRecordRepository;

    @InjectMocks
    private PublishService publishService;

    private Content approvedContent;
    private Content publishedContent;
    private Content pendingContent;
    private Content rejectedContent;

    @BeforeEach
    void setUp() {
        approvedContent = TestDataBuilder.buildApprovedContent();
        publishedContent = TestDataBuilder.buildPublishedContent();
        pendingContent = TestDataBuilder.buildPendingReviewContent();
        rejectedContent = TestDataBuilder.buildRejectedContent();
    }

    @Nested
    @DisplayName("发布流程测试")
    class PublishProcessTests {

        @Test
        @DisplayName("立即发布 - 成功发布内容")
        void testExecutePublish_Immediate_Success() {
            String contentId = approvedContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildWebPublishRequest(contentId);
            PublishRecord mockRecord = TestDataBuilder.buildWebPublishRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

            PublishRecord result = publishService.executePublish(request);

            assertNotNull(result);
            assertEquals("published", result.getPublishStatus());
            assertEquals(contentId, result.getContentId());

            verify(publishRecordRepository, times(1)).save(any(PublishRecord.class));
            verify(contentService, times(1)).updateStatus(eq(contentId), eq("published"), anyString());
            verify(historyRecordRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("发布流程 - 验证发布信息完整性")
        void testExecutePublish_ValidateInformation() {
            String contentId = approvedContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildPublishExecuteRequest(
                contentId, "web", true);
            request.setPublisherId("publisher_manager_001");
            request.setPublisherName("内容发布经理");

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
                PublishRecord record = invocation.getArgument(0);
                assertEquals(contentId, record.getContentId());
                assertEquals("web", record.getPublishChannel());
                assertEquals("publisher_manager_001", record.getPublisherId());
                assertEquals("内容发布经理", record.getPublisherName());
                return record;
            });
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

            publishService.executePublish(request);

            verify(publishRecordRepository, times(1)).save(any(PublishRecord.class));
        }

        @Test
        @DisplayName("发布流程 - 验证发布时间设置")
        void testExecutePublish_ValidatePublishTime() {
            String contentId = approvedContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildWebPublishRequest(contentId);

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
                PublishRecord record = invocation.getArgument(0);
                assertNotNull(record.getPublishTime());
                assertTrue(record.getPublishTime().isBefore(LocalDateTime.now().plusMinutes(1)));
                assertTrue(record.getPublishTime().isAfter(LocalDateTime.now().minusMinutes(1)));
                return record;
            });
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

            publishService.executePublish(request);
        }

        @Test
        @DisplayName("发布流程 - 内容不存在时拒绝发布")
        void testExecutePublish_ContentNotFound() {
            String contentId = "non_existent_001";
            PublishExecuteRequest request = TestDataBuilder.buildWebPublishRequest(contentId);

            when(contentService.getContentById(anyString())).thenThrow(new BusinessException(404, "内容不存在"));

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                publishService.executePublish(request);
            });

            assertEquals(404, exception.getCode());
        }
    }

    @Nested
    @DisplayName("发布状态验证测试")
    class PublishStatusValidationTests {

        @Test
        @DisplayName("状态验证 - 已发布内容不可重复发布")
        void testExecutePublish_AlreadyPublished() {
            String contentId = publishedContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildWebPublishRequest(contentId);

            when(contentService.getContentById(anyString())).thenReturn(publishedContent);

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                publishService.executePublish(request);
            });

            assertEquals(400, exception.getCode());
            assertEquals("内容已发布", exception.getMessage());
        }

        @Test
        @DisplayName("状态验证 - 未审核内容不可发布")
        void testExecutePublish_NotApproved() {
            String contentId = pendingContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildWebPublishRequest(contentId);

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                publishService.executePublish(request);
            });

            assertEquals(400, exception.getCode());
            assertEquals("内容未审核通过，无法发布", exception.getMessage());
        }

        @Test
        @DisplayName("状态验证 - 已拒绝内容不可发布")
        void testExecutePublish_Rejected() {
            String contentId = rejectedContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildWebPublishRequest(contentId);

            when(contentService.getContentById(anyString())).thenReturn(rejectedContent);

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                publishService.executePublish(request);
            });

            assertEquals(400, exception.getCode());
            assertEquals("内容未审核通过，无法发布", exception.getMessage());
        }

        @Test
        @DisplayName("状态验证 - 已审核内容可以发布")
        void testExecutePublish_ApprovedAllowed() {
            String contentId = approvedContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildWebPublishRequest(contentId);
            PublishRecord mockRecord = TestDataBuilder.buildWebPublishRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

            PublishRecord result = publishService.executePublish(request);

            assertNotNull(result);
            assertEquals("published", result.getPublishStatus());
        }
    }

    @Nested
    @DisplayName("发布渠道测试")
    class PublishChannelTests {

        @Test
        @DisplayName("网站发布 - 验证网站渠道配置")
        void testWebPublish_ValidateChannel() {
            String contentId = approvedContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildWebPublishRequest(contentId);
            PublishRecord mockRecord = TestDataBuilder.buildWebPublishRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
                PublishRecord record = invocation.getArgument(0);
                assertEquals("web", record.getPublishChannel());
                return record;
            });
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

            publishService.executePublish(request);

            verify(publishRecordRepository, times(1)).save(any(PublishRecord.class));
        }

        @Test
        @DisplayName("移动端发布 - 验证移动端渠道配置")
        void testMobilePublish_ValidateChannel() {
            String contentId = approvedContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildMobilePublishRequest(contentId);
            PublishRecord mockRecord = TestDataBuilder.buildMobilePublishRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
                PublishRecord record = invocation.getArgument(0);
                assertEquals("mobile", record.getPublishChannel());
                return record;
            });
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

            publishService.executePublish(request);
        }

        @Test
        @DisplayName("多渠道发布 - 不同渠道不同配置")
        void testMultiChannelPublish_DifferentConfig() {
            String contentId = approvedContent.getContentId();

            String[] channels = TestDataBuilder.getPublishChannels();

            for (String channel : channels) {
                PublishExecuteRequest request = TestDataBuilder.buildPublishExecuteRequest(contentId, channel, true);
                PublishRecord record = TestDataBuilder.buildPublishRecord(contentId, channel, "published");

                when(contentService.getContentById(anyString())).thenReturn(approvedContent);
                when(publishRecordRepository.save(any(PublishRecord.class))).thenReturn(record);
                when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

                PublishRecord result = publishService.executePublish(request);

                assertEquals(channel, result.getPublishChannel());
            }
        }

        @Test
        @DisplayName("渠道配置 - 发布配置信息完整")
        void testChannelConfig_ConfigurationComplete() {
            String contentId = approvedContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildWebPublishRequest(contentId);

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
                PublishRecord record = invocation.getArgument(0);
                Map<String, String> config = record.getPublishConfig();
                assertNotNull(config);
                assertEquals("immediate", config.get("schedule"));
                return record;
            });
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

            publishService.executePublish(request);
        }
    }

    @Nested
    @DisplayName("定时发布测试")
    class ScheduledPublishTests {

        @Test
        @DisplayName("定时发布 - 验证定时配置")
        void testScheduledPublish_ValidateSchedule() {
            String contentId = approvedContent.getContentId();
            LocalDateTime scheduleTime = LocalDateTime.now().plusHours(24);
            PublishExecuteRequest request = TestDataBuilder.buildScheduledPublishRequest(contentId, scheduleTime);
            PublishRecord mockRecord = TestDataBuilder.buildScheduledPublishRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
                PublishRecord record = invocation.getArgument(0);
                assertNotNull(record.getScheduleTime());
                Map<String, String> config = record.getPublishConfig();
                assertNotNull(config);
                assertEquals("scheduled", config.get("schedule"));
                return record;
            });
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

            publishService.executePublish(request);
        }

        @Test
        @DisplayName("立即发布 - 验证立即发布配置")
        void testImmediatePublish_ValidateImmediate() {
            String contentId = approvedContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildWebPublishRequest(contentId);
            PublishRecord mockRecord = TestDataBuilder.buildWebPublishRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
                PublishRecord record = invocation.getArgument(0);
                Map<String, String> config = record.getPublishConfig();
                assertNotNull(config);
                assertEquals("immediate", config.get("schedule"));
                assertNull(record.getScheduleTime());
                return record;
            });
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

            publishService.executePublish(request);
        }

        @Test
        @DisplayName("双场景对比 - 立即发布vs定时发布")
        void testDualPublishScenario_Compare() {
            String contentId1 = approvedContent.getContentId();
            String contentId2 = "content_scheduled_001";

            Content approvedContent2 = TestDataBuilder.buildApprovedContent();
            approvedContent2.setContentId(contentId2);

            PublishExecuteRequest immediateRequest = TestDataBuilder.buildWebPublishRequest(contentId1);
            PublishExecuteRequest scheduledRequest = TestDataBuilder.buildScheduledPublishRequest(contentId2);

            when(contentService.getContentById(contentId1)).thenReturn(approvedContent);
            when(contentService.getContentById(contentId2)).thenReturn(approvedContent2);

            when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
                PublishRecord record = invocation.getArgument(0);
                return record;
            });

            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
                String cid = invocation.getArgument(0);
                return cid.equals(contentId1) ? approvedContent : approvedContent2;
            });

            PublishRecord immediateResult = publishService.executePublish(immediateRequest);
            PublishRecord scheduledResult = publishService.executePublish(scheduledRequest);

            assertEquals("immediate", immediateResult.getPublishConfig().get("schedule"));
            assertEquals("scheduled", scheduledResult.getPublishConfig().get("schedule"));
            assertNull(immediateResult.getScheduleTime());
            assertNotNull(scheduledResult.getScheduleTime());
        }

        @Test
        @DisplayName("定时发布时间 - 未来时间验证")
        void testScheduledPublish_FutureTime() {
            String contentId = approvedContent.getContentId();
            LocalDateTime futureTime = LocalDateTime.now().plusDays(7);
            PublishExecuteRequest request = TestDataBuilder.buildScheduledPublishRequest(contentId, futureTime);

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
                PublishRecord record = invocation.getArgument(0);
                assertNotNull(record.getScheduleTime());
                assertTrue(record.getScheduleTime().isAfter(LocalDateTime.now()));
                return record;
            });
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

            publishService.executePublish(request);
        }
    }

    @Nested
    @DisplayName("发布预警机制测试")
    class PublishWarningTests {

        @Test
        @DisplayName("发布记录创建 - 自动创建发布记录")
        void testWarning_CreateRecord() {
            String contentId = approvedContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildWebPublishRequest(contentId);
            PublishRecord mockRecord = TestDataBuilder.buildWebPublishRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

            PublishRecord result = publishService.executePublish(request);

            assertNotNull(result.getPublishId());
            assertNotNull(result.getPublishTime());
        }

        @Test
        @DisplayName("发布历史记录 - 记录发布操作")
        void testWarning_HistoryRecord() {
            String contentId = approvedContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildWebPublishRequest(contentId);
            PublishRecord mockRecord = TestDataBuilder.buildWebPublishRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

            publishService.executePublish(request);

            verify(historyRecordRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("发布通知 - 发布员信息完整")
        void testWarning_PublisherInfoComplete() {
            String contentId = approvedContent.getContentId();
            PublishExecuteRequest request = TestDataBuilder.buildPublishExecuteRequest(
                contentId, "web", true);
            request.setPublisherId("publisher_senior_001");
            request.setPublisherName("资深发布员王工");
            PublishRecord mockRecord = TestDataBuilder.buildWebPublishRecord(contentId);
            mockRecord.setPublisherId("publisher_senior_001");
            mockRecord.setPublisherName("资深发布员王工");

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

            PublishRecord result = publishService.executePublish(request);

            assertEquals("publisher_senior_001", result.getPublisherId());
            assertEquals("资深发布员王工", result.getPublisherName());
        }

        @Test
        @DisplayName("下架预警 - 下架操作记录")
        void testWarning_UnpublishRecord() {
            String contentId = publishedContent.getContentId();
            PublishRecord mockUnpublishRecord = TestDataBuilder.buildUnpublishRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(publishedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenReturn(mockUnpublishRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(publishedContent);

            PublishRecord result = publishService.unpublishContent(contentId, "operator_001", "操作员");

            assertNotNull(result);
            assertEquals("unpublished", result.getPublishStatus());
            verify(historyRecordRepository, times(1)).save(any());
        }
    }

    @Nested
    @DisplayName("发布下架测试")
    class UnpublishTests {

        @Test
        @DisplayName("下架内容 - 成功下架已发布内容")
        void testUnpublish_Success() {
            String contentId = publishedContent.getContentId();
            PublishRecord mockRecord = TestDataBuilder.buildUnpublishRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(publishedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(publishedContent);

            PublishRecord result = publishService.unpublishContent(contentId, "operator_001", "操作员");

            assertNotNull(result);
            assertEquals("unpublished", result.getPublishStatus());
            verify(contentService, times(1)).updateStatus(eq(contentId), eq("unpublished"), anyString());
        }

        @Test
        @DisplayName("下架内容 - 未发布内容不可下架")
        void testUnpublish_NotPublished() {
            String contentId = approvedContent.getContentId();

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                publishService.unpublishContent(contentId, "operator_001", "操作员");
            });

            assertEquals(400, exception.getCode());
            assertEquals("内容未发布，无法下架", exception.getMessage());
        }

        @Test
        @DisplayName("下架配置 - 下架配置信息完整")
        void testUnpublish_ConfigurationComplete() {
            String contentId = publishedContent.getContentId();

            when(contentService.getContentById(anyString())).thenReturn(publishedContent);
            when(publishRecordRepository.save(any(PublishRecord.class))).thenAnswer(invocation -> {
                PublishRecord record = invocation.getArgument(0);
                Map<String, String> config = record.getPublishConfig();
                assertNotNull(config);
                assertEquals("unpublish", config.get("action"));
                return record;
            });
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(publishedContent);

            publishService.unpublishContent(contentId, "operator_001", "操作员");
        }
    }

    @Nested
    @DisplayName("发布查询测试")
    class PublishQueryTests {

        @Test
        @DisplayName("按内容ID查询发布记录")
        void testGetPublishesByContentId() {
            String contentId = approvedContent.getContentId();
            List<PublishRecord> mockRecords = TestDataBuilder.buildPublishRecordList(contentId, 3);

            when(publishRecordRepository.findByContentId(anyString())).thenReturn(mockRecords);

            List<PublishRecord> result = publishService.getPublishesByContentId(contentId);

            assertNotNull(result);
            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("按状态查询发布记录")
        void testGetPublishesByStatus() {
            List<PublishRecord> mockRecords = TestDataBuilder.buildPublishRecordList("content_001", 5);

            when(publishRecordRepository.findByPublishStatus("published")).thenReturn(mockRecords);

            List<PublishRecord> result = publishService.getPublishesByStatus("published");

            assertNotNull(result);
            assertEquals(5, result.size());
        }

        @Test
        @DisplayName("按渠道查询发布记录")
        void testGetPublishesByChannel() {
            List<PublishRecord> mockRecords = TestDataBuilder.buildPublishRecordList("content_001", 2);

            when(publishRecordRepository.findByPublishChannel("web")).thenReturn(mockRecords);

            List<PublishRecord> result = publishService.getPublishesByChannel("web");

            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("按ID查询单个发布记录")
        void testGetPublishById_Success() {
            String publishId = "publish_test_001";
            PublishRecord mockRecord = TestDataBuilder.buildWebPublishRecord("content_001");
            mockRecord.setPublishId(publishId);

            when(publishRecordRepository.findById(anyString())).thenReturn(Optional.of(mockRecord));

            PublishRecord result = publishService.getPublishById(publishId);

            assertNotNull(result);
            assertEquals(publishId, result.getPublishId());
        }

        @Test
        @DisplayName("按ID查询发布记录不存在时抛出异常")
        void testGetPublishById_NotFound() {
            when(publishRecordRepository.findById(anyString())).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                publishService.getPublishById("non_existent");
            });

            assertEquals(404, exception.getCode());
            assertEquals("发布记录不存在", exception.getMessage());
        }

        @Test
        @DisplayName("查询所有发布记录")
        void testGetAllPublishes() {
            List<PublishRecord> mockRecords = TestDataBuilder.buildPublishRecordList("content_001", 10);

            when(publishRecordRepository.findAll()).thenReturn(mockRecords);

            List<PublishRecord> result = publishService.getAllPublishes();

            assertNotNull(result);
            assertEquals(10, result.size());
        }
    }
}
