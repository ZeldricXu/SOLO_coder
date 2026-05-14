package com.cms.service;

import com.cms.builder.TestDataBuilder;
import com.cms.dto.ReviewProcessRequest;
import com.cms.entity.Content;
import com.cms.entity.ReviewRecord;
import com.cms.exception.BusinessException;
import com.cms.repository.HistoryRecordRepository;
import com.cms.repository.ReviewRecordRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("审核模块测试")
class ReviewModuleTest {

    @Mock
    private ReviewRecordRepository reviewRecordRepository;

    @Mock
    private ContentService contentService;

    @Mock
    private HistoryRecordRepository historyRecordRepository;

    @InjectMocks
    private ReviewService reviewService;

    private Content pendingContent;
    private Content approvedContent;
    private Content publishedContent;
    private Content rejectedContent;

    @BeforeEach
    void setUp() {
        pendingContent = TestDataBuilder.buildPendingReviewContent();
        approvedContent = TestDataBuilder.buildApprovedContent();
        publishedContent = TestDataBuilder.buildPublishedContent();
        rejectedContent = TestDataBuilder.buildRejectedContent();
    }

    @Nested
    @DisplayName("审核流程测试")
    class ReviewProcessTests {

        @Test
        @DisplayName("审核通过 - 成功审核通过内容")
        void testProcessReview_Approved_Success() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(contentId);
            ReviewRecord mockRecord = TestDataBuilder.buildApprovedReviewRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            ReviewRecord result = reviewService.processReview(request);

            assertNotNull(result);
            assertEquals("approved", result.getReviewStatus());
            assertEquals(contentId, result.getContentId());

            verify(reviewRecordRepository, times(1)).save(any(ReviewRecord.class));
            verify(contentService, times(1)).updateStatus(eq(contentId), eq("approved"), anyString());
            verify(historyRecordRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("审核拒绝 - 成功审核拒绝内容")
        void testProcessReview_Rejected_Success() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildRejectReviewRequest(contentId);
            ReviewRecord mockRecord = TestDataBuilder.buildRejectedReviewRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            ReviewRecord result = reviewService.processReview(request);

            assertNotNull(result);
            assertEquals("rejected", result.getReviewStatus());
            assertEquals(contentId, result.getContentId());

            verify(reviewRecordRepository, times(1)).save(any(ReviewRecord.class));
            verify(contentService, times(1)).updateStatus(eq(contentId), eq("rejected"), anyString());
            verify(historyRecordRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("审核流程 - 验证审核信息完整性")
        void testProcessReview_ValidateInformation() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(
                contentId, "approved", "reviewer_senior", "高级审核员");
            ReviewRecord mockRecord = TestDataBuilder.buildReviewRecord(
                contentId, "approved", "reviewer_senior", "高级审核员");

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenAnswer(invocation -> {
                ReviewRecord record = invocation.getArgument(0);
                assertEquals(contentId, record.getContentId());
                assertEquals("approved", record.getReviewStatus());
                assertEquals("reviewer_senior", record.getReviewerId());
                assertEquals("高级审核员", record.getReviewerName());
                return record;
            });
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            reviewService.processReview(request);

            verify(reviewRecordRepository, times(1)).save(any(ReviewRecord.class));
        }

        @Test
        @DisplayName("审核流程 - 验证审核时间设置")
        void testProcessReview_ValidateReviewTime() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(contentId);

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenAnswer(invocation -> {
                ReviewRecord record = invocation.getArgument(0);
                assertNotNull(record.getReviewTime());
                assertTrue(record.getReviewTime().isBefore(LocalDateTime.now().plusMinutes(1)));
                assertTrue(record.getReviewTime().isAfter(LocalDateTime.now().minusMinutes(1)));
                return record;
            });
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            reviewService.processReview(request);
        }

        @Test
        @DisplayName("审核流程 - 内容不存在时拒绝审核")
        void testProcessReview_ContentNotFound() {
            String contentId = "non_existent_001";
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(contentId);

            when(contentService.getContentById(anyString())).thenThrow(new BusinessException(404, "内容不存在"));

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                reviewService.processReview(request);
            });

            assertEquals(404, exception.getCode());
        }
    }

    @Nested
    @DisplayName("审核状态验证测试")
    class ReviewStatusValidationTests {

        @Test
        @DisplayName("状态验证 - 已审核内容不可重复审核")
        void testProcessReview_AlreadyApproved() {
            String contentId = approvedContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(contentId);

            when(contentService.getContentById(anyString())).thenReturn(approvedContent);

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                reviewService.processReview(request);
            });

            assertEquals(400, exception.getCode());
            assertEquals("内容已审核", exception.getMessage());
        }

        @Test
        @DisplayName("状态验证 - 已发布内容不可审核")
        void testProcessReview_AlreadyPublished() {
            String contentId = publishedContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(contentId);

            when(contentService.getContentById(anyString())).thenReturn(publishedContent);

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                reviewService.processReview(request);
            });

            assertEquals(400, exception.getCode());
            assertEquals("内容已发布", exception.getMessage());
        }

        @Test
        @DisplayName("状态验证 - 非待审核内容不可审核")
        void testProcessReview_InvalidStatus() {
            String contentId = rejectedContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(contentId);

            when(contentService.getContentById(anyString())).thenReturn(rejectedContent);

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                reviewService.processReview(request);
            });

            assertEquals(400, exception.getCode());
            assertEquals("内容状态不允许审核", exception.getMessage());
        }

        @Test
        @DisplayName("状态验证 - 无效审核状态值")
        void testProcessReview_InvalidReviewStatus() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(contentId);
            request.setReviewStatus("invalid_status");

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                reviewService.processReview(request);
            });

            assertEquals(400, exception.getCode());
            assertEquals("无效的审核状态", exception.getMessage());
        }

        @Test
        @DisplayName("状态验证 - 待审核内容可以审核")
        void testProcessReview_PendingStatusAllowed() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(contentId);
            ReviewRecord mockRecord = TestDataBuilder.buildApprovedReviewRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            ReviewRecord result = reviewService.processReview(request);

            assertNotNull(result);
            assertEquals("approved", result.getReviewStatus());
        }
    }

    @Nested
    @DisplayName("审核双场景测试")
    class ReviewDualScenarioTests {

        @Test
        @DisplayName("审核通过场景 - 更新内容状态为已通过")
        void testApprovedScenario_UpdateStatus() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(contentId);
            ReviewRecord mockRecord = TestDataBuilder.buildApprovedReviewRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            reviewService.processReview(request);

            verify(contentService, times(1)).updateStatus(eq(contentId), eq("approved"), anyString());
        }

        @Test
        @DisplayName("审核通过场景 - 记录审核通过意见")
        void testApprovedScenario_RecordApprovalComment() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(contentId);
            request.setReviewComment("内容符合要求，可以发布");

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenAnswer(invocation -> {
                ReviewRecord record = invocation.getArgument(0);
                assertTrue(record.getReviewComment().contains("符合要求"));
                return record;
            });
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            reviewService.processReview(request);
        }

        @Test
        @DisplayName("审核拒绝场景 - 更新内容状态为已拒绝")
        void testRejectedScenario_UpdateStatus() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildRejectReviewRequest(contentId);
            ReviewRecord mockRecord = TestDataBuilder.buildRejectedReviewRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            reviewService.processReview(request);

            verify(contentService, times(1)).updateStatus(eq(contentId), eq("rejected"), anyString());
        }

        @Test
        @DisplayName("审核拒绝场景 - 记录审核拒绝原因")
        void testRejectedScenario_RecordRejectionReason() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildRejectReviewRequest(contentId);
            request.setReviewComment("内容存在敏感词汇，需修改后重新提交");

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenAnswer(invocation -> {
                ReviewRecord record = invocation.getArgument(0);
                assertTrue(record.getReviewComment().contains("敏感词汇"));
                return record;
            });
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            reviewService.processReview(request);
        }

        @Test
        @DisplayName("双场景对比 - 通过和拒绝记录不同审核员")
        void testDualScenario_DifferentReviewers() {
            String contentId1 = pendingContent.getContentId();
            String contentId2 = "content_another_001";

            ReviewProcessRequest approvedRequest = TestDataBuilder.buildReviewProcessRequest(
                contentId1, "approved", "reviewer_001", "审核员A");
            ReviewProcessRequest rejectedRequest = TestDataBuilder.buildReviewProcessRequest(
                contentId2, "rejected", "reviewer_002", "审核员B");

            Content pendingContent2 = TestDataBuilder.buildPendingReviewContent();
            pendingContent2.setContentId(contentId2);

            when(contentService.getContentById(contentId1)).thenReturn(pendingContent);
            when(contentService.getContentById(contentId2)).thenReturn(pendingContent2);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
                String cid = invocation.getArgument(0);
                return cid.equals(contentId1) ? pendingContent : pendingContent2;
            });

            ReviewRecord approvedResult = reviewService.processReview(approvedRequest);
            ReviewRecord rejectedResult = reviewService.processReview(rejectedRequest);

            assertEquals("reviewer_001", approvedResult.getReviewerId());
            assertEquals("审核员A", approvedResult.getReviewerName());
            assertEquals("approved", approvedResult.getReviewStatus());

            assertEquals("reviewer_002", rejectedResult.getReviewerId());
            assertEquals("审核员B", rejectedResult.getReviewerName());
            assertEquals("rejected", rejectedResult.getReviewStatus());
        }
    }

    @Nested
    @DisplayName("审核紧急程度测试")
    class ReviewUrgencyLevelTests {

        @Test
        @DisplayName("正常紧急程度 - 标准审核流程")
        void testNormalUrgency_NormalProcess() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewWithUrgency(contentId, "normal");
            ReviewRecord mockRecord = TestDataBuilder.buildReviewRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            ReviewRecord result = reviewService.processReview(request);

            assertNotNull(result);
            verify(contentService, times(1)).updateStatus(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("高紧急程度 - 高级审核员处理")
        void testHighUrgency_SeniorReviewer() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewWithUrgency(contentId, "high");
            ReviewRecord mockRecord = TestDataBuilder.buildUrgentReviewRecord(contentId, "approved");

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            ReviewRecord result = reviewService.processReview(request);

            assertNotNull(result);
            assertEquals("reviewer_senior", mockRecord.getReviewerId());
            assertTrue(mockRecord.getReviewComment().contains("紧急"));
        }

        @Test
        @DisplayName("紧急程度 - 不同紧急程度不同处理优先级")
        void testUrgencyLevels_DifferentPriorities() {
            String[] levels = TestDataBuilder.getReviewUrgencyLevels();

            for (String level : levels) {
                String contentId = "content_" + level + "_001";
                Content content = TestDataBuilder.buildPendingReviewContent();
                content.setContentId(contentId);

                ReviewProcessRequest request = TestDataBuilder.buildReviewWithUrgency(contentId, level);
                request.setReviewComment("【" + level + "】审核处理");

                ReviewRecord record = TestDataBuilder.buildReviewRecord(contentId);
                record.setReviewComment(request.getReviewComment());

                when(contentService.getContentById(contentId)).thenReturn(content);
                when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(record);
                when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(content);

                ReviewRecord result = reviewService.processReview(request);

                assertTrue(result.getReviewComment().contains(level));
            }
        }

        @Test
        @DisplayName("紧急程度影响 - 关键级别标记")
        void testCriticalLevel_SpecialMarking() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewWithUrgency(contentId, "critical");
            request.setReviewComment("【critical】重要内容，需要重点关注");
            ReviewRecord mockRecord = TestDataBuilder.buildUrgentReviewRecord(contentId, "approved");
            mockRecord.setReviewComment("【critical】重要内容，需要重点关注");

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            ReviewRecord result = reviewService.processReview(request);

            assertTrue(result.getReviewComment().contains("critical"));
            assertTrue(result.getReviewComment().contains("重要"));
        }
    }

    @Nested
    @DisplayName("审核提醒机制测试")
    class ReviewReminderTests {

        @Test
        @DisplayName("审核记录创建 - 自动创建提醒记录")
        void testReminder_CreateRecord() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(contentId);
            ReviewRecord mockRecord = TestDataBuilder.buildApprovedReviewRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            ReviewRecord result = reviewService.processReview(request);

            assertNotNull(result.getReviewId());
            assertNotNull(result.getReviewTime());
        }

        @Test
        @DisplayName("审核历史记录 - 记录审核操作")
        void testReminder_HistoryRecord() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(contentId);
            ReviewRecord mockRecord = TestDataBuilder.buildApprovedReviewRecord(contentId);

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            reviewService.processReview(request);

            verify(historyRecordRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("审核通知 - 审核员信息完整")
        void testReminder_ReviewerInfoComplete() {
            String contentId = pendingContent.getContentId();
            ReviewProcessRequest request = TestDataBuilder.buildReviewProcessRequest(
                contentId, "approved", "reviewer_005", "资深审核员李工");
            ReviewRecord mockRecord = TestDataBuilder.buildReviewRecord(
                contentId, "approved", "reviewer_005", "资深审核员李工");

            when(contentService.getContentById(anyString())).thenReturn(pendingContent);
            when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(mockRecord);
            when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

            ReviewRecord result = reviewService.processReview(request);

            assertEquals("reviewer_005", result.getReviewerId());
            assertEquals("资深审核员李工", result.getReviewerName());
        }
    }

    @Nested
    @DisplayName("审核查询测试")
    class ReviewQueryTests {

        @Test
        @DisplayName("按内容ID查询审核记录")
        void testGetReviewsByContentId() {
            String contentId = pendingContent.getContentId();
            List<ReviewRecord> mockRecords = TestDataBuilder.buildReviewRecordList(contentId, 3);

            when(reviewRecordRepository.findByContentId(anyString())).thenReturn(mockRecords);

            List<ReviewRecord> result = reviewService.getReviewsByContentId(contentId);

            assertNotNull(result);
            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("按审核员查询审核记录")
        void testGetReviewsByReviewerId() {
            String reviewerId = "reviewer_001";
            List<ReviewRecord> mockRecords = TestDataBuilder.buildReviewRecordList("content_001", 5);

            when(reviewRecordRepository.findByReviewerId(anyString())).thenReturn(mockRecords);

            List<ReviewRecord> result = reviewService.getReviewsByReviewerId(reviewerId);

            assertNotNull(result);
            assertEquals(5, result.size());
        }

        @Test
        @DisplayName("按状态查询审核记录")
        void testGetReviewsByStatus() {
            List<ReviewRecord> mockRecords = TestDataBuilder.buildReviewRecordList("content_001", 2);

            when(reviewRecordRepository.findByReviewStatus("approved")).thenReturn(mockRecords);

            List<ReviewRecord> result = reviewService.getReviewsByStatus("approved");

            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("按ID查询单个审核记录")
        void testGetReviewById_Success() {
            String reviewId = "review_test_001";
            ReviewRecord mockRecord = TestDataBuilder.buildApprovedReviewRecord("content_001");
            mockRecord.setReviewId(reviewId);

            when(reviewRecordRepository.findById(anyString())).thenReturn(Optional.of(mockRecord));

            ReviewRecord result = reviewService.getReviewById(reviewId);

            assertNotNull(result);
            assertEquals(reviewId, result.getReviewId());
        }

        @Test
        @DisplayName("按ID查询审核记录不存在时抛出异常")
        void testGetReviewById_NotFound() {
            when(reviewRecordRepository.findById(anyString())).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                reviewService.getReviewById("non_existent");
            });

            assertEquals(404, exception.getCode());
            assertEquals("审核记录不存在", exception.getMessage());
        }

        @Test
        @DisplayName("查询所有审核记录")
        void testGetAllReviews() {
            List<ReviewRecord> mockRecords = TestDataBuilder.buildReviewRecordList("content_001", 10);

            when(reviewRecordRepository.findAll()).thenReturn(mockRecords);

            List<ReviewRecord> result = reviewService.getAllReviews();

            assertNotNull(result);
            assertEquals(10, result.size());
        }
    }
}
