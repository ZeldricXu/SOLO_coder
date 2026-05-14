package com.mobilestore.service;

import com.mobilestore.entity.App;
import com.mobilestore.entity.ApprovalLog;
import com.mobilestore.entity.Notification;
import com.mobilestore.entity.Version;
import com.mobilestore.exception.PermissionDeniedException;
import com.mobilestore.exception.ResourceNotFoundException;
import com.mobilestore.repository.AppRepository;
import com.mobilestore.repository.ApprovalLogRepository;
import com.mobilestore.repository.NotificationRepository;
import com.mobilestore.repository.VersionRepository;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("版本发布与审批服务测试")
class VersionServiceTest extends BaseServiceTest {

    @Mock
    private VersionRepository versionRepository;

    @Mock
    private AppRepository appRepository;

    @Mock
    private ApprovalLogRepository approvalLogRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ApprovalPermissionService approvalPermissionService;

    @InjectMocks
    private VersionService versionService;

    @Nested
    @DisplayName("版本发布测试")
    class SubmitVersionTests {

        @Test
        @DisplayName("开发者提交版本应成功")
        void submitVersion_shouldSucceedForDeveloper() {
            Version request = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            App app = TestDataBuilder.buildApp("app_001", "测试应用");
            request.setSubmitter("dev_001");

            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));
            doNothing().when(approvalPermissionService).checkSubmitPermission("dev_001");
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));

            Version result = versionService.submitVersion("app_001", request);

            assertNotNull(result);
            assertEquals("app_001", result.getAppId());
            assertEquals("pending_approval", result.getPublishStatus());
            assertNotNull(result.getSubmittedAt());
            verify(versionRepository, times(1)).save(any(Version.class));
            verify(notificationRepository, times(1)).save(any(Notification.class));
        }

        @Test
        @DisplayName("非开发者提交版本应被拒绝")
        void submitVersion_shouldThrowPermissionException() {
            Version request = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            request.setSubmitter("reviewer_001");

            App app = TestDataBuilder.buildApp("app_001", "测试应用");
            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));
            doThrow(new PermissionDeniedException("PERMISSION_DENIED", "不具备提交权限"))
                .when(approvalPermissionService).checkSubmitPermission("reviewer_001");

            PermissionDeniedException exception = assertThrows(
                PermissionDeniedException.class,
                () -> versionService.submitVersion("app_001", request)
            );

            assertEquals("PERMISSION_DENIED", exception.getErrorCode());
            verify(versionRepository, never()).save(any(Version.class));
        }

        @Test
        @DisplayName("应用不存在时应抛出异常")
        void submitVersion_shouldThrowExceptionWhenAppNotFound() {
            Version request = TestDataBuilder.buildPendingVersion("invalid_app", "1.0.0");
            when(appRepository.findById("invalid_app")).thenReturn(Optional.empty());

            ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> versionService.submitVersion("invalid_app", request)
            );

            assertTrue(exception.getMessage().contains("应用"));
        }

        @Test
        @DisplayName("版本提交后状态应为待审批")
        void submitVersion_shouldSetPendingApprovalStatus() {
            Version request = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            App app = TestDataBuilder.buildApp("app_001", "测试应用");
            request.setSubmitter("dev_001");

            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));

            Version result = versionService.submitVersion("app_001", request);

            assertEquals("pending_approval", result.getPublishStatus());
        }
    }

    @Nested
    @DisplayName("审批流程测试")
    class ApprovalProcessTests {

        @Test
        @DisplayName("审批人员审批通过应成功")
        void processApproval_approve_shouldSucceed() {
            Version pendingVersion = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            String versionId = pendingVersion.getVersionId();
            String approver = "reviewer_001";

            when(versionRepository.findById(versionId)).thenReturn(Optional.of(pendingVersion));
            doNothing().when(approvalPermissionService).checkApprovalPermission(approver);
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));
            when(approvalLogRepository.save(any(ApprovalLog.class))).thenAnswer(inv -> inv.getArgument(0));
            when(appRepository.findById("app_001")).thenReturn(Optional.of(TestDataBuilder.buildApp("app_001", "测试应用")));

            Version result = versionService.processApproval(versionId, "approved", approver, "测试通过");

            assertNotNull(result);
            assertEquals("approved", result.getPublishStatus());
            assertEquals(approver, result.getApprover());
            assertNotNull(result.getApprovedAt());
        }

        @Test
        @DisplayName("审批人员审批拒绝应成功")
        void processApproval_reject_shouldSucceed() {
            Version pendingVersion = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            String versionId = pendingVersion.getVersionId();
            String approver = "reviewer_001";

            when(versionRepository.findById(versionId)).thenReturn(Optional.of(pendingVersion));
            doNothing().when(approvalPermissionService).checkRejectPermission(approver);
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));
            when(approvalLogRepository.save(any(ApprovalLog.class))).thenAnswer(inv -> inv.getArgument(0));

            Version result = versionService.processApproval(versionId, "rejected", approver, "测试拒绝");

            assertNotNull(result);
            assertEquals("rejected", result.getPublishStatus());
            assertEquals(approver, result.getApprover());
            assertNotNull(result.getRejectedAt());
        }

        @Test
        @DisplayName("非审批人员审批应被拒绝")
        void processApproval_shouldThrowPermissionExceptionForNonReviewer() {
            Version pendingVersion = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            String versionId = pendingVersion.getVersionId();
            String devUser = "dev_001";

            when(versionRepository.findById(versionId)).thenReturn(Optional.of(pendingVersion));
            doThrow(new PermissionDeniedException("PERMISSION_DENIED", "不具备审批权限"))
                .when(approvalPermissionService).checkApprovalPermission(devUser);

            PermissionDeniedException exception = assertThrows(
                PermissionDeniedException.class,
                () -> versionService.processApproval(versionId, "approved", devUser, "测试")
            );

            assertEquals("PERMISSION_DENIED", exception.getErrorCode());
            verify(versionRepository, never()).save(any(Version.class));
        }

        @Test
        @DisplayName("重复审批应抛出异常")
        void processApproval_shouldThrowExceptionWhenAlreadyApproved() {
            Version approvedVersion = TestDataBuilder.buildApprovedVersion("app_001", "1.0.0");
            String versionId = approvedVersion.getVersionId();

            when(versionRepository.findById(versionId)).thenReturn(Optional.of(approvedVersion));

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> versionService.processApproval(versionId, "approved", "reviewer_001", "测试")
            );

            assertTrue(exception.getMessage().contains("已审批") || exception.getMessage().contains("重复"));
        }

        @Test
        @DisplayName("版本不存在时应抛出异常")
        void processApproval_shouldThrowExceptionWhenVersionNotFound() {
            when(versionRepository.findById("invalid_version")).thenReturn(Optional.empty());

            ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> versionService.processApproval("invalid_version", "approved", "reviewer_001", "测试")
            );

            assertTrue(exception.getMessage().contains("版本"));
        }
    }

    @Nested
    @DisplayName("审批日志测试")
    class ApprovalLogTests {

        @Test
        @DisplayName("审批通过应创建审批日志")
        void processApproval_shouldCreateApprovalLog() {
            Version pendingVersion = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            String versionId = pendingVersion.getVersionId();
            String approver = "reviewer_001";
            String comment = "功能完整，审批通过";

            when(versionRepository.findById(versionId)).thenReturn(Optional.of(pendingVersion));
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));
            when(approvalLogRepository.save(any(ApprovalLog.class))).thenAnswer(inv -> inv.getArgument(0));
            when(appRepository.findById("app_001")).thenReturn(Optional.of(TestDataBuilder.buildApp("app_001", "测试应用")));

            versionService.processApproval(versionId, "approved", approver, comment);

            ArgumentCaptor<ApprovalLog> logCaptor = ArgumentCaptor.forClass(ApprovalLog.class);
            verify(approvalLogRepository, times(1)).save(logCaptor.capture());
            ApprovalLog savedLog = logCaptor.getValue();

            assertEquals(versionId, savedLog.getVersionId());
            assertEquals("approve", savedLog.getAction());
            assertEquals(approver, savedLog.getOperator());
            assertEquals(comment, savedLog.getComment());
        }

        @Test
        @DisplayName("审批拒绝应创建审批日志")
        void processApproval_reject_shouldCreateApprovalLog() {
            Version pendingVersion = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            String versionId = pendingVersion.getVersionId();
            String approver = "reviewer_001";
            String comment = "存在安全漏洞，需要修复";

            when(versionRepository.findById(versionId)).thenReturn(Optional.of(pendingVersion));
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));
            when(approvalLogRepository.save(any(ApprovalLog.class))).thenAnswer(inv -> inv.getArgument(0));

            versionService.processApproval(versionId, "rejected", approver, comment);

            ArgumentCaptor<ApprovalLog> logCaptor = ArgumentCaptor.forClass(ApprovalLog.class);
            verify(approvalLogRepository, times(1)).save(logCaptor.capture());
            ApprovalLog savedLog = logCaptor.getValue();

            assertEquals(versionId, savedLog.getVersionId());
            assertEquals("reject", savedLog.getAction());
            assertEquals(approver, savedLog.getOperator());
            assertEquals(comment, savedLog.getComment());
        }
    }

    @Nested
    @DisplayName("通知推送测试")
    class NotificationTests {

        @Test
        @DisplayName("版本提交后应通知审批人员")
        void submitVersion_shouldNotifyReviewers() {
            Version request = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            App app = TestDataBuilder.buildApp("app_001", "测试应用");
            request.setSubmitter("dev_001");

            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));

            versionService.submitVersion("app_001", request);

            ArgumentCaptor<Notification> notifCaptor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository, times(1)).save(notifCaptor.capture());
            Notification savedNotif = notifCaptor.getValue();

            assertEquals("version_approval", savedNotif.getType());
            assertEquals("reviewer_group", savedNotif.getRecipientId());
            assertTrue(savedNotif.getTitle().contains("待审批"));
        }

        @Test
        @DisplayName("审批通过应通知开发者")
        void processApproval_approve_shouldNotifyDeveloper() {
            Version pendingVersion = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            pendingVersion.setSubmitter("dev_001");
            String versionId = pendingVersion.getVersionId();

            when(versionRepository.findById(versionId)).thenReturn(Optional.of(pendingVersion));
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));
            when(approvalLogRepository.save(any(ApprovalLog.class))).thenAnswer(inv -> inv.getArgument(0));
            when(appRepository.findById("app_001")).thenReturn(Optional.of(TestDataBuilder.buildApp("app_001", "测试应用")));

            versionService.processApproval(versionId, "approved", "reviewer_001", "通过");

            verify(notificationRepository, times(1)).save(any(Notification.class));
        }

        @Test
        @DisplayName("审批拒绝应通知开发者")
        void processApproval_reject_shouldNotifyDeveloper() {
            Version pendingVersion = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            pendingVersion.setSubmitter("dev_001");
            String versionId = pendingVersion.getVersionId();

            when(versionRepository.findById(versionId)).thenReturn(Optional.of(pendingVersion));
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));
            when(approvalLogRepository.save(any(ApprovalLog.class))).thenAnswer(inv -> inv.getArgument(0));

            versionService.processApproval(versionId, "rejected", "reviewer_001", "拒绝");

            verify(notificationRepository, times(1)).save(any(Notification.class));
        }
    }

    @Nested
    @DisplayName("状态时序测试")
    class StatusTransitionTests {

        @Test
        @DisplayName("状态流转：提交 -> 待审批")
        void statusTransition_submitToPending() {
            Version request = TestDataBuilder.buildVersion("v1", "app_001", "1.0.0", "draft");
            App app = TestDataBuilder.buildApp("app_001", "测试应用");
            request.setSubmitter("dev_001");

            when(appRepository.findById("app_001")).thenReturn(Optional.of(app));
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));

            Version result = versionService.submitVersion("app_001", request);

            assertEquals("pending_approval", result.getPublishStatus());
            assertNotNull(result.getSubmittedAt());
        }

        @Test
        @DisplayName("状态流转：待审批 -> 已通过")
        void statusTransition_pendingToApproved() {
            Version pendingVersion = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            String versionId = pendingVersion.getVersionId();
            LocalDateTime beforeApproval = pendingVersion.getSubmittedAt();

            when(versionRepository.findById(versionId)).thenReturn(Optional.of(pendingVersion));
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));
            when(approvalLogRepository.save(any(ApprovalLog.class))).thenAnswer(inv -> inv.getArgument(0));
            when(appRepository.findById("app_001")).thenReturn(Optional.of(TestDataBuilder.buildApp("app_001", "测试应用")));

            Version result = versionService.processApproval(versionId, "approved", "reviewer_001", "通过");

            assertEquals("approved", result.getPublishStatus());
            assertNotNull(result.getApprovedAt());
            assertTrue(result.getApprovedAt().isAfter(beforeApproval));
        }

        @Test
        @DisplayName("状态流转：待审批 -> 已拒绝")
        void statusTransition_pendingToRejected() {
            Version pendingVersion = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            String versionId = pendingVersion.getVersionId();
            LocalDateTime beforeRejection = pendingVersion.getSubmittedAt();

            when(versionRepository.findById(versionId)).thenReturn(Optional.of(pendingVersion));
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));
            when(approvalLogRepository.save(any(ApprovalLog.class))).thenAnswer(inv -> inv.getArgument(0));

            Version result = versionService.processApproval(versionId, "rejected", "reviewer_001", "拒绝");

            assertEquals("rejected", result.getPublishStatus());
            assertNotNull(result.getRejectedAt());
            assertTrue(result.getRejectedAt().isAfter(beforeRejection));
        }
    }

    @Nested
    @DisplayName("权限检查测试")
    class PermissionCheckTests {

        @Test
        @DisplayName("checkApprovalPermission方法被正确调用")
        void approvalPermissionService_shouldBeCalled() {
            Version pendingVersion = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            String versionId = pendingVersion.getVersionId();
            String approver = "reviewer_001";

            when(versionRepository.findById(versionId)).thenReturn(Optional.of(pendingVersion));
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));
            when(approvalLogRepository.save(any(ApprovalLog.class))).thenAnswer(inv -> inv.getArgument(0));
            when(appRepository.findById("app_001")).thenReturn(Optional.of(TestDataBuilder.buildApp("app_001", "测试应用")));

            versionService.processApproval(versionId, "approved", approver, "通过");

            verify(approvalPermissionService, times(1)).checkApprovalPermission(approver);
        }

        @Test
        @DisplayName("checkRejectPermission方法被正确调用")
        void rejectPermissionService_shouldBeCalled() {
            Version pendingVersion = TestDataBuilder.buildPendingVersion("app_001", "1.0.0");
            String versionId = pendingVersion.getVersionId();
            String approver = "reviewer_001";

            when(versionRepository.findById(versionId)).thenReturn(Optional.of(pendingVersion));
            when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));
            when(approvalLogRepository.save(any(ApprovalLog.class))).thenAnswer(inv -> inv.getArgument(0));

            versionService.processApproval(versionId, "rejected", approver, "拒绝");

            verify(approvalPermissionService, times(1)).checkRejectPermission(approver);
        }
    }
}
