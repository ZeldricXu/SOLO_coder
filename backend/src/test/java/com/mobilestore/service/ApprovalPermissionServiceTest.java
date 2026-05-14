package com.mobilestore.service;

import com.mobilestore.entity.UserRole;
import com.mobilestore.exception.PermissionDeniedException;
import com.mobilestore.repository.UserRoleRepository;
import com.mobilestore.test.BaseServiceTest;
import com.mobilestore.test.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("审批权限服务测试")
class ApprovalPermissionServiceTest extends BaseServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private ApprovalPermissionService approvalPermissionService;

    @Nested
    @DisplayName("角色权限映射测试")
    class RolePermissionMappingTests {

        @Test
        @DisplayName("审批人员应具备审批和拒绝权限")
        void reviewer_shouldHaveApproveAndRejectPermissions() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(anyString())).thenReturn(null);
            UserRole reviewerRole = TestDataBuilder.buildReviewerRole();
            when(userRoleRepository.findByUserId("reviewer_001")).thenReturn(Optional.of(reviewerRole));

            boolean canApprove = approvalPermissionService.hasPermission("reviewer_001", "version:approve");
            boolean canReject = approvalPermissionService.hasPermission("reviewer_001", "version:reject");
            boolean canSubmit = approvalPermissionService.hasPermission("reviewer_001", "version:submit");

            assertTrue(canApprove, "审批人员应有审批权限");
            assertTrue(canReject, "审批人员应有拒绝权限");
            assertFalse(canSubmit, "审批人员不应有提交权限");
        }

        @Test
        @DisplayName("开发者只具备提交和查看权限")
        void developer_shouldOnlyHaveSubmitAndViewPermissions() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(anyString())).thenReturn(null);
            UserRole devRole = TestDataBuilder.buildDeveloperRole();
            when(userRoleRepository.findByUserId("dev_001")).thenReturn(Optional.of(devRole));

            boolean canSubmit = approvalPermissionService.hasPermission("dev_001", "version:submit");
            boolean canViewApproval = approvalPermissionService.hasPermission("dev_001", "version:view_approval");
            boolean canApprove = approvalPermissionService.hasPermission("dev_001", "version:approve");

            assertTrue(canSubmit, "开发者应有提交权限");
            assertTrue(canViewApproval, "开发者应有查看审批权限");
            assertFalse(canApprove, "开发者不应有审批权限");
        }

        @Test
        @DisplayName("管理员具备所有权限")
        void admin_shouldHaveAllPermissions() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(anyString())).thenReturn(null);
            UserRole adminRole = TestDataBuilder.buildAdminRole();
            when(userRoleRepository.findByUserId("admin_001")).thenReturn(Optional.of(adminRole));

            boolean canApprove = approvalPermissionService.hasPermission("admin_001", "version:approve");
            boolean canReject = approvalPermissionService.hasPermission("admin_001", "version:reject");
            boolean canSubmit = approvalPermissionService.hasPermission("admin_001", "version:submit");
            boolean canView = approvalPermissionService.hasPermission("admin_001", "version:view_approval");

            assertTrue(canApprove, "管理员应有审批权限");
            assertTrue(canReject, "管理员应有拒绝权限");
            assertTrue(canSubmit, "管理员应有提交权限");
            assertTrue(canView, "管理员应有查看权限");
        }
    }

    @Nested
    @DisplayName("权限校验方法测试")
    class PermissionCheckTests {

        @Test
        @DisplayName("审批人员调用checkApprovalPermission应通过")
        void checkApprovalPermission_shouldPassForReviewer() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(anyString())).thenReturn(null);
            UserRole reviewerRole = TestDataBuilder.buildReviewerRole();
            when(userRoleRepository.findByUserId("reviewer_001")).thenReturn(Optional.of(reviewerRole));

            assertDoesNotThrow(() -> approvalPermissionService.checkApprovalPermission("reviewer_001"));
        }

        @Test
        @DisplayName("开发者调用checkApprovalPermission应抛出异常")
        void checkApprovalPermission_shouldThrowExceptionForDeveloper() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(anyString())).thenReturn(null);
            UserRole devRole = TestDataBuilder.buildDeveloperRole();
            when(userRoleRepository.findByUserId("dev_001")).thenReturn(Optional.of(devRole));

            PermissionDeniedException exception = assertThrows(
                PermissionDeniedException.class,
                () -> approvalPermissionService.checkApprovalPermission("dev_001")
            );

            assertEquals("PERMISSION_DENIED", exception.getErrorCode());
            assertTrue(exception.getMessage().contains("不具备审批权限"));
        }

        @Test
        @DisplayName("审批人员调用checkRejectPermission应通过")
        void checkRejectPermission_shouldPassForReviewer() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(anyString())).thenReturn(null);
            UserRole reviewerRole = TestDataBuilder.buildReviewerRole();
            when(userRoleRepository.findByUserId("reviewer_001")).thenReturn(Optional.of(reviewerRole));

            assertDoesNotThrow(() -> approvalPermissionService.checkRejectPermission("reviewer_001"));
        }

        @Test
        @DisplayName("开发者调用checkSubmitPermission应通过")
        void checkSubmitPermission_shouldPassForDeveloper() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(anyString())).thenReturn(null);
            UserRole devRole = TestDataBuilder.buildDeveloperRole();
            when(userRoleRepository.findByUserId("dev_001")).thenReturn(Optional.of(devRole));

            assertDoesNotThrow(() -> approvalPermissionService.checkSubmitPermission("dev_001"));
        }
    }

    @Nested
    @DisplayName("权限缓存测试")
    class PermissionCacheTests {

        @Test
        @DisplayName("缓存命中时应直接返回结果")
        void hasPermission_shouldUseCacheWhenAvailable() {
            String cacheKey = "permission:reviewer_001:version:approve";
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(cacheKey)).thenReturn(true);

            boolean result = approvalPermissionService.hasPermission("reviewer_001", "version:approve");

            assertTrue(result);
            verify(userRoleRepository, never()).findByUserId(anyString());
        }

        @Test
        @DisplayName("缓存未命中时应查询数据库并缓存")
        void hasPermission_shouldQueryDatabaseAndCacheWhenMiss() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(anyString())).thenReturn(null);
            UserRole reviewerRole = TestDataBuilder.buildReviewerRole();
            when(userRoleRepository.findByUserId("reviewer_001")).thenReturn(Optional.of(reviewerRole));

            boolean result = approvalPermissionService.hasPermission("reviewer_001", "version:approve");

            assertTrue(result);
            verify(valueOperations, times(1)).set(anyString(), eq(true), eq(30L), eq(TimeUnit.MINUTES));
        }

        @Test
        @DisplayName("用户不存在时应返回false且缓存")
        void hasPermission_shouldReturnFalseWhenUserNotFound() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(anyString())).thenReturn(null);
            when(userRoleRepository.findByUserId("unknown_user")).thenReturn(Optional.empty());

            boolean result = approvalPermissionService.hasPermission("unknown_user", "version:approve");

            assertFalse(result);
            verify(valueOperations, times(1)).set(anyString(), eq(false), eq(30L), eq(TimeUnit.MINUTES));
        }
    }

    @Nested
    @DisplayName("权限检查结果测试")
    class PermissionCheckResultTests {

        @Test
        @DisplayName("checkPermission返回有权限结果")
        void checkPermission_shouldReturnAllowedResult() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(anyString())).thenReturn(null);
            UserRole reviewerRole = TestDataBuilder.buildReviewerRole();
            when(userRoleRepository.findByUserId("reviewer_001")).thenReturn(Optional.of(reviewerRole));

            ApprovalPermissionService.PermissionCheckResult result = 
                approvalPermissionService.checkPermission("reviewer_001", "version:approve");

            assertTrue(result.isAllowed());
            assertEquals("reviewer_001", result.getUserId());
            assertEquals("version:approve", result.getPermission());
            assertTrue(result.getMessage().contains("有权限"));
        }

        @Test
        @DisplayName("checkPermission返回无权限结果")
        void checkPermission_shouldReturnDeniedResult() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(anyString())).thenReturn(null);
            UserRole devRole = TestDataBuilder.buildDeveloperRole();
            when(userRoleRepository.findByUserId("dev_001")).thenReturn(Optional.of(devRole));

            ApprovalPermissionService.PermissionCheckResult result = 
                approvalPermissionService.checkPermission("dev_001", "version:approve");

            assertFalse(result.isAllowed());
            assertEquals("dev_001", result.getUserId());
            assertEquals("version:approve", result.getPermission());
            assertTrue(result.getMessage().contains("不具备"));
        }
    }
}
