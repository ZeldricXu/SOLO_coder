package com.mobilestore.service;

import com.mobilestore.entity.App;
import com.mobilestore.entity.Feedback;
import com.mobilestore.entity.Notification;
import com.mobilestore.exception.ResourceNotFoundException;
import com.mobilestore.repository.AppRepository;
import com.mobilestore.repository.FeedbackRepository;
import com.mobilestore.repository.NotificationRepository;
import com.mobilestore.test.BaseServiceTest;
import com.mobilestore.test.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("反馈服务测试")
class FeedbackServiceTest extends BaseServiceTest {

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private AppRepository appRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private FeedbackClassificationService classificationService;

    @InjectMocks
    private FeedbackService feedbackService;

    @Nested
    @DisplayName("反馈提交测试")
    class SubmitFeedbackTests {

        @Test
        @DisplayName("提交反馈应成功并自动分类")
        void submitFeedback_shouldSucceedWithAutoClassification() {
            Feedback request = TestDataBuilder.buildFeedback("f1", "app_001", "应用闪退");
            App app = TestDataBuilder.buildApp("app_001", "测试应用");

            FeedbackClassificationService.ClassificationResult classificationResult =
                new FeedbackClassificationService.ClassificationResult(
                    "bug_report", "high", "tech_support_001", Arrays.asList("闪退")
                );

            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));
            when(classificationService.classify(null, "应用闪退", 3)).thenReturn(classificationResult);
            when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> inv.getArgument(0));

            Feedback result = feedbackService.submitFeedback("app_001", request);

            assertNotNull(result);
            assertEquals("bug_report", result.getFeedbackType());
            assertEquals("high", result.getPriority());
            assertEquals("tech_support_001", result.getAssignee());
            verify(feedbackRepository, times(1)).save(any(Feedback.class));
        }

        @Test
        @DisplayName("应用不存在时应抛出异常")
        void submitFeedback_shouldThrowExceptionWhenAppNotFound() {
            Feedback request = TestDataBuilder.buildFeedback("f1", "invalid_app", "测试反馈");
            when(appRepository.findById("invalid_app")).thenReturn(Optional.empty());

            ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> feedbackService.submitFeedback("invalid_app", request)
            );

            assertTrue(exception.getMessage().contains("应用"));
        }

        @Test
        @DisplayName("空内容反馈应被拒绝")
        void submitFeedback_shouldRejectEmptyContent() {
            Feedback request = TestDataBuilder.buildFeedback("f1", "app_001", "");
            App app = TestDataBuilder.buildApp("app_001", "测试应用");

            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> feedbackService.submitFeedback("app_001", request)
            );

            assertTrue(exception.getMessage().contains("内容") || exception.getMessage().contains("不能为空"));
        }

        @Test
        @DisplayName("用户ID不能为空")
        void submitFeedback_shouldRejectWithoutUserId() {
            Feedback request = TestDataBuilder.buildFeedback("f1", "app_001", "测试反馈");
            request.setUserId(null);
            App app = TestDataBuilder.buildApp("app_001", "测试应用");

            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> feedbackService.submitFeedback("app_001", request)
            );

            assertTrue(exception.getMessage().contains("用户"));
        }

        @Test
        @DisplayName("评分应限制在1-5之间")
        void submitFeedback_shouldRejectInvalidRating() {
            Feedback request = TestDataBuilder.buildFeedback("f1", "app_001", "测试反馈");
            request.setRating(6);
            App app = TestDataBuilder.buildApp("app_001", "测试应用");

            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> feedbackService.submitFeedback("app_001", request)
            );

            assertTrue(exception.getMessage().contains("评分") || exception.getMessage().contains("rating"));
        }

        @Test
        @DisplayName("提交后应通知处理人")
        void submitFeedback_shouldNotifyAssignee() {
            Feedback request = TestDataBuilder.buildFeedback("f1", "app_001", "应用闪退");
            App app = TestDataBuilder.buildApp("app_001", "测试应用");

            FeedbackClassificationService.ClassificationResult classificationResult =
                new FeedbackClassificationService.ClassificationResult(
                    "bug_report", "high", "tech_support_001", Arrays.asList("闪退")
                );

            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));
            when(classificationService.classify(any(), any(), anyInt())).thenReturn(classificationResult);
            when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> inv.getArgument(0));

            feedbackService.submitFeedback("app_001", request);

            ArgumentCaptor<Notification> notifCaptor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository, times(1)).save(notifCaptor.capture());
            Notification savedNotif = notifCaptor.getValue();

            assertEquals("feedback_assignment", savedNotif.getType());
            assertEquals("tech_support_001", savedNotif.getRecipientId());
        }
    }

    @Nested
    @DisplayName("反馈状态管理测试")
    class FeedbackStatusTests {

        @Test
        @DisplayName("反馈初始状态应为pending")
        void submitFeedback_initialStatusShouldBePending() {
            Feedback request = TestDataBuilder.buildFeedback("f1", "app_001", "测试反馈");
            App app = TestDataBuilder.buildApp("app_001", "测试应用");

            FeedbackClassificationService.ClassificationResult classificationResult =
                new FeedbackClassificationService.ClassificationResult(
                    "other", "medium", "support_001", Arrays.asList()
                );

            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));
            when(classificationService.classify(any(), any(), anyInt())).thenReturn(classificationResult);
            when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> inv.getArgument(0));

            Feedback result = feedbackService.submitFeedback("app_001", request);

            assertEquals("pending", result.getStatus());
        }

        @Test
        @DisplayName("处理反馈应将状态改为processing")
        void processFeedback_shouldSetProcessing() {
            Feedback pendingFeedback = TestDataBuilder.buildBugFeedback("app_001", "应用闪退");
            String feedbackId = pendingFeedback.getFeedbackId();
            pendingFeedback.setStatus("pending");

            when(feedbackRepository.findById(feedbackId)).thenReturn(Optional.of(pendingFeedback));
            when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> inv.getArgument(0));

            Feedback result = feedbackService.processFeedback(feedbackId, "support_001");

            assertEquals("processing", result.getStatus());
            assertEquals("support_001", result.getProcessedBy());
            assertNotNull(result.getProcessedAt());
        }

        @Test
        @DisplayName("完成反馈应将状态改为resolved")
        void resolveFeedback_shouldSetResolved() {
            Feedback processingFeedback = TestDataBuilder.buildBugFeedback("app_001", "应用闪退");
            String feedbackId = processingFeedback.getFeedbackId();
            processingFeedback.setStatus("processing");
            processingFeedback.setProcessedBy("support_001");

            when(feedbackRepository.findById(feedbackId)).thenReturn(Optional.of(processingFeedback));
            when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> inv.getArgument(0));

            Feedback result = feedbackService.resolveFeedback(feedbackId, "问题已修复", "support_001");

            assertEquals("resolved", result.getStatus());
            assertEquals("问题已修复", result.getResolution());
            assertNotNull(result.getResolvedAt());
        }

        @Test
        @DisplayName("重复处理应抛出异常")
        void processFeedback_shouldRejectAlreadyProcessed() {
            Feedback processingFeedback = TestDataBuilder.buildBugFeedback("app_001", "应用闪退");
            String feedbackId = processingFeedback.getFeedbackId();
            processingFeedback.setStatus("processing");

            when(feedbackRepository.findById(feedbackId)).thenReturn(Optional.of(processingFeedback));

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> feedbackService.processFeedback(feedbackId, "support_001")
            );

            assertTrue(exception.getMessage().contains("已处理") || exception.getMessage().contains("重复"));
        }

        @Test
        @DisplayName("非processing状态不能直接resolved应成功")
        void resolveFeedback_shouldRejectNotProcessing() {
            Feedback pendingFeedback = TestDataBuilder.buildBugFeedback("app_001", "应用闪退");
            String feedbackId = pendingFeedback.getFeedbackId();
            pendingFeedback.setStatus("pending");

            when(feedbackRepository.findById(feedbackId)).thenReturn(Optional.of(pendingFeedback));

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> feedbackService.resolveFeedback(feedbackId, "已解决", "support_001")
            );

            assertTrue(exception.getMessage().contains("状态") || exception.getMessage().contains("处理"));
        }

        @Test
        @DisplayName("关闭反馈应将状态改为closed")
        void closeFeedback_shouldSetClosed() {
            Feedback resolvedFeedback = TestDataBuilder.buildBugFeedback("app_001", "应用闪退");
            String feedbackId = resolvedFeedback.getFeedbackId();
            resolvedFeedback.setStatus("resolved");

            when(feedbackRepository.findById(feedbackId)).thenReturn(Optional.of(resolvedFeedback));
            when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> inv.getArgument(0));

            Feedback result = feedbackService.closeFeedback(feedbackId);

            assertEquals("closed", result.getStatus());
            assertNotNull(result.getClosedAt());
        }
    }

    @Nested
    @DisplayName("反馈查询测试")
    class FeedbackQueryTests {

        @Test
        @DisplayName("按应用ID查询反馈")
        void getFeedbacksByApp_shouldReturnFiltered() {
            List<Feedback> feedbacks = Arrays.asList(
                TestDataBuilder.buildBugFeedback("app_001", "反馈1"),
                TestDataBuilder.buildBugFeedback("app_001", "反馈2")
            );

            when(feedbackRepository.findByAppIdOrderByCreatedAtDesc("app_001")).thenReturn(feedbacks);

            List<Feedback> result = feedbackService.getFeedbacksByApp("app_001", null, null);

            assertEquals(2, result.size());
            verify(feedbackRepository, times(1)).findByAppIdOrderByCreatedAtDesc("app_001"));
        }

        @Test
        @DisplayName("按状态过滤反馈")
        void getFeedbacksByApp_withStatus_shouldFilter() {
            List<Feedback> feedbacks = Arrays.asList(
                TestDataBuilder.buildBugFeedback("app_001", "反馈1")
            );

            when(feedbackRepository.findByAppIdAndStatusOrderByPriorityDescCreatedAtDesc("app_001", "pending")).thenReturn(feedbacks);

            List<Feedback> result = feedbackService.getFeedbacksByApp("app_001", "pending", null);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("按类型过滤反馈")
        void getFeedbacksByApp_withType_shouldFilter() {
            List<Feedback> feedbacks = Arrays.asList(
                TestDataBuilder.buildFeatureFeedback("app_001", "建议1")
            );

            when(feedbackRepository.findByAppIdAndFeedbackTypeOrderByPriorityDescCreatedAtDesc("app_001", "feature_request")).thenReturn(feedbacks);

            List<Feedback> result = feedbackService.getFeedbacksByApp("app_001", null, "feature_request");

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("按优先级排序")
        void getFeedbacksByApp_withStatusAndType_shouldFilter() {
            List<Feedback> feedbacks = Arrays.asList(
                TestDataBuilder.buildBugFeedback("app_001", "反馈1")
            );

            when(feedbackRepository.findByAppIdAndStatusAndFeedbackTypeOrderByPriorityDescCreatedAtDesc(
                "app_001", "pending", "bug_report"
            )).thenReturn(feedbacks);

            List<Feedback> result = feedbackService.getFeedbacksByApp("app_001", "pending", "bug_report");

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("查询单个反馈")
        void getFeedback_shouldReturn() {
            Feedback feedback = TestDataBuilder.buildBugFeedback("app_001", "应用闪退");
            String feedbackId = feedback.getFeedbackId();

            when(feedbackRepository.findById(feedbackId)).thenReturn(Optional.of(feedback));

            Feedback result = feedbackService.getFeedback(feedbackId);

            assertNotNull(result);
            assertEquals(feedbackId, result.getFeedbackId());
        }

        @Test
        @DisplayName("反馈不存在应抛出异常")
        void getFeedback_shouldThrowExceptionWhenNotFound() {
            when(feedbackRepository.findById("invalid_id")).thenReturn(Optional.empty());

            ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> feedbackService.getFeedback("invalid_id")
            );

            assertTrue(exception.getMessage().contains("反馈"));
        }
    }

    @Nested
    @DisplayName("优先级标记测试")
    class PriorityMarkingTests {

        @Test
        @DisplayName("Bug反馈应为高优先级")
        void bugFeedback_shouldBeHighPriority() {
            Feedback request = TestDataBuilder.buildFeedback("f1", "app_001", "应用闪退崩溃");
            App app = TestDataBuilder.buildApp("app_001", "测试应用");

            FeedbackClassificationService.ClassificationResult classificationResult =
                new FeedbackClassificationService.ClassificationResult(
                    "bug_report", "high", "tech_support_001", Arrays.asList("闪退", "崩溃")
                );

            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));
            when(classificationService.classify(any(), any(), anyInt())).thenReturn(classificationResult);
            when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> inv.getArgument(0));

            Feedback result = feedbackService.submitFeedback("app_001", request);

            assertEquals("high", result.getPriority());
        }

        @Test
        @DisplayName("功能建议应为中优先级")
        void featureRequest_shouldBeMediumPriority() {
            Feedback request = TestDataBuilder.buildFeedback("f1", "app_001", "建议增加新功能");
            App app = TestDataBuilder.buildApp("app_001", "测试应用");

            FeedbackClassificationService.ClassificationResult classificationResult =
                new FeedbackClassificationService.ClassificationResult(
                    "feature_request", "medium", "product_001", Arrays.asList("建议")
                );

            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));
            when(classificationService.classify(any(), any(), anyInt())).thenReturn(classificationResult);
            when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> inv.getArgument(0));

            Feedback result = feedbackService.submitFeedback("app_001", request);

            assertEquals("medium", result.getPriority());
        }

        @Test
        @DisplayName("咨询应为低优先级")
        void question_shouldBeLowPriority() {
            Feedback request = TestDataBuilder.buildFeedback("f1", "app_001", "请问怎么使用");
            App app = TestDataBuilder.buildApp("app_001", "测试应用");

            FeedbackClassificationService.ClassificationResult classificationResult =
                new FeedbackClassificationService.ClassificationResult(
                    "question", "low", "support_001", Arrays.asList("请问")
                );

            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));
            when(classificationService.classify(any(), any(), anyInt())).thenReturn(classificationResult);
            when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> inv.getArgument(0));

            Feedback result = feedbackService.submitFeedback("app_001", request);

            assertEquals("low", result.getPriority());
        }
    }

    @Nested
    @DisplayName("分类预览测试")
    class ClassificationPreviewTests {

        @Test
        @DisplayName("预览分类不应创建反馈")
        void previewClassification_shouldNotCreateFeedback() {
            Feedback request = TestDataBuilder.buildFeedback("f1", "app_001", "应用闪退");

            FeedbackClassificationService.ClassificationResult classificationResult =
                new FeedbackClassificationService.ClassificationResult(
                    "bug_report", "high", "tech_support_001", Arrays.asList("闪退")
                );

            when(classificationService.classify(null, "应用闪退", 3)).thenReturn(classificationResult);

            FeedbackClassificationService.ClassificationResult result =
                feedbackService.previewClassification(null, "应用闪退", 3);

            assertNotNull(result);
            assertEquals("bug_report", result.getFeedbackType());
            assertEquals("high", result.getPriority());
            verify(feedbackRepository, never()).save(any(Feedback.class));
        }

        @Test
        @DisplayName("预览分类应返回分类规则")
        void getClassificationRules_shouldReturnRules() {
            FeedbackClassificationService.ClassificationRules mockRules =
                new FeedbackClassificationService.ClassificationRules();
            mockRules.setBugKeywords(Arrays.asList("闪退", "崩溃"));
            mockRules.setFeatureKeywords(Arrays.asList("建议", "希望"));

            when(classificationService.getClassificationRules()).thenReturn(mockRules);

            FeedbackClassificationService.ClassificationRules result =
                feedbackService.getClassificationRules();

            assertNotNull(result);
            assertTrue(result.getBugKeywords().contains("闪退"));
        }
    }
}
