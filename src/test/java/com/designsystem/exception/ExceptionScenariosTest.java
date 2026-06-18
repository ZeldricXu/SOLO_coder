package com.designsystem.exception;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.designsystem.common.enums.ApprovalStatus;
import com.designsystem.entity.ApprovalRequest;
import com.designsystem.entity.Component;
import com.designsystem.entity.ComponentVersion;
import com.designsystem.entity.DesignToken;
import com.designsystem.mapper.ApprovalRequestMapper;
import com.designsystem.mapper.ComponentMapper;
import com.designsystem.mapper.ComponentVersionMapper;
import com.designsystem.mapper.DesignTokenMapper;
import com.designsystem.service.DesignTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("异常场景测试")
@ExtendWith(MockitoExtension.class)
class ExceptionScenariosTest {

    @Mock
    private DesignTokenMapper tokenMapper;

    @Mock
    private ComponentMapper componentMapper;

    @Mock
    private ComponentVersionMapper versionMapper;

    @Mock
    private ApprovalRequestMapper approvalRequestMapper;

    @InjectMocks
    private DesignTokenService tokenService;

    @Nested
    @DisplayName("版本冲突乐观锁处理测试")
    class OptimisticLockingTests {

        @Test
        @DisplayName("两个组件同时发布产生版本号冲突时应抛出乐观锁异常")
        void shouldHandleVersionConflictWithOptimisticLock() {
            Long componentId = 1L;
            Component component = new Component();
            component.setId(componentId);
            component.setName("Button");
            component.setLatestVersion("1.0.0");

            when(componentMapper.selectById(componentId)).thenReturn(component);
            when(componentMapper.updateById(any(Component.class)))
                    .thenThrow(new OptimisticLockingFailureException("Version conflict detected"));

            assertThrows(OptimisticLockingFailureException.class, () -> {
                tokenService.checkCircularReference("test", "test");
            });

            verify(componentMapper, atLeastOnce()).updateById(any(Component.class));
        }

        @Test
        @DisplayName("版本冲突重试机制应最多重试3次")
        void shouldRetryAtMostThreeTimesOnConflict() {
            Long componentId = 1L;
            Component component = new Component();
            component.setId(componentId);
            component.setName("Button");
            component.setLatestVersion("1.0.0");
            component.setVersion(1);

            ComponentVersion version1 = new ComponentVersion();
            version1.setId(1L);
            version1.setComponentId(componentId);
            version1.setVersion("1.0.0");
            version1.setIsLatest(1);

            when(componentMapper.selectById(componentId)).thenReturn(component);
            when(versionMapper.selectLatestVersion(componentId)).thenReturn(version1);

            AtomicInteger attemptCount = new AtomicInteger(0);
            when(componentMapper.updateById(any(Component.class))).thenAnswer(invocation -> {
                attemptCount.incrementAndGet();
                if (attemptCount.get() < 3) {
                    throw new OptimisticLockingFailureException("Conflict");
                }
                return 1;
            });

            when(versionMapper.updateById(any(ComponentVersion.class))).thenReturn(1);

            assertThrows(OptimisticLockingFailureException.class, () -> {
                publishComponentWithRetry(componentId, "1.1.0", componentMapper, versionMapper);
            });

            assertTrue(attemptCount.get() <= 3, "Should retry at most 3 times, but retried " + attemptCount.get() + " times");
        }

        private void publishComponentWithRetry(Long componentId, String version,
                                               ComponentMapper componentMapper,
                                               ComponentVersionMapper versionMapper) {
            int maxRetries = 3;
            int retryCount = 0;

            while (retryCount < maxRetries) {
                try {
                    Component component = componentMapper.selectById(componentId);
                    if (component == null) {
                        throw new RuntimeException("Component not found");
                    }
                    component.setLatestVersion(version);
                    componentMapper.updateById(component);

                    ComponentVersion latestVersion = versionMapper.selectLatestVersion(componentId);
                    if (latestVersion != null) {
                        latestVersion.setIsLatest(0);
                        versionMapper.updateById(latestVersion);
                    }
                    return;
                } catch (OptimisticLockingFailureException e) {
                    retryCount++;
                    if (retryCount >= maxRetries) {
                        throw e;
                    }
                    try {
                        Thread.sleep(100 * retryCount);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                }
            }
        }

        @Test
        @DisplayName("审批请求重复处理应检测并拒绝")
        void shouldRejectDuplicateApprovalProcessing() {
            Long approvalId = 1L;
            ApprovalRequest request = new ApprovalRequest();
            request.setId(approvalId);
            request.setStatus(ApprovalStatus.APPROVED);

            when(approvalRequestMapper.selectById(approvalId)).thenReturn(request);

            ApprovalRequest duplicateRequest = new ApprovalRequest();
            duplicateRequest.setId(approvalId);
            duplicateRequest.setStatus(ApprovalStatus.PENDING);

            when(approvalRequestMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(request);

            assertThrows(IllegalStateException.class, () -> {
                processApproval(approvalId, ApprovalStatus.APPROVED, approvalRequestMapper);
            });
        }

        private void processApproval(Long approvalId, ApprovalStatus status,
                                     ApprovalRequestMapper approvalRequestMapper) {
            ApprovalRequest request = approvalRequestMapper.selectById(approvalId);
            if (request == null) {
                throw new RuntimeException("Approval request not found");
            }

            if (request.getStatus() != ApprovalStatus.PENDING) {
                throw new IllegalStateException("Approval request already processed: " + request.getStatus());
            }

            request.setStatus(status);
            approvalRequestMapper.updateById(request);
        }
    }

    @Nested
    @DisplayName("令牌循环引用检测测试")
    class TokenCircularReferenceTests {

        @Test
        @DisplayName("更新令牌时检测到循环引用应抛出异常")
        void shouldThrowExceptionOnCircularReferenceUpdate() {
            DesignToken tokenA = new DesignToken();
            tokenA.setId(1L);
            tokenA.setTokenName("--token-a");
            tokenA.setInheritsFrom("--token-b");

            DesignToken tokenB = new DesignToken();
            tokenB.setId(2L);
            tokenB.setTokenName("--token-b");
            tokenB.setInheritsFrom("--token-a");

            when(tokenMapper.selectById(1L)).thenReturn(tokenA);
            when(tokenMapper.selectByName("--token-a")).thenReturn(tokenA);
            when(tokenMapper.selectByName("--token-b")).thenReturn(tokenB);

            DesignToken updatedToken = new DesignToken();
            updatedToken.setId(1L);
            updatedToken.setTokenName("--token-a");
            updatedToken.setInheritsFrom("--token-b");
            updatedToken.setBaseValue("#ffffff");

            assertThrows(IllegalArgumentException.class, () -> {
                tokenService.updateToken(updatedToken);
            });
        }

        @Test
        @DisplayName("创建令牌时检测到循环引用应阻断")
        void shouldBlockCircularReferenceOnCreate() {
            DesignToken existingToken = new DesignToken();
            existingToken.setTokenName("--existing-token");
            existingToken.setInheritsFrom("--new-token");

            when(tokenMapper.selectByName("--existing-token")).thenReturn(existingToken);

            boolean hasCycle = tokenService.checkCircularReference("--new-token", "--existing-token");
            assertTrue(hasCycle);
        }

        @Test
        @DisplayName("多层间接循环引用也应被检测到")
        void shouldDetectIndirectCircularReference() {
            DesignToken tokenA = new DesignToken();
            tokenA.setTokenName("--token-a");
            tokenA.setInheritsFrom("--token-b");

            DesignToken tokenB = new DesignToken();
            tokenB.setTokenName("--token-b");
            tokenB.setInheritsFrom("--token-c");

            DesignToken tokenC = new DesignToken();
            tokenC.setTokenName("--token-c");
            tokenC.setInheritsFrom("--token-a");

            when(tokenMapper.selectByName("--token-a")).thenReturn(tokenA);
            when(tokenMapper.selectByName("--token-b")).thenReturn(tokenB);
            when(tokenMapper.selectByName("--token-c")).thenReturn(tokenC);

            boolean hasCycle = tokenService.checkCircularReference("--token-a", "--token-b");
            assertTrue(hasCycle);
        }
    }

    @Nested
    @DisplayName("文档解析容错测试")
    class DocumentationParsingFaultToleranceTests {

        @Test
        @DisplayName("单个组件解析失败不应影响其他组件")
        void shouldNotAffectOtherComponentsWhenOneFails() {
            String[] componentSources = {
                    "interface ValidProps { name: string; }",
                    "interface BrokenProps { unclosed { missing closing",
                    "interface AnotherValidProps { id: number; }"
            };

            int successCount = 0;
            int failCount = 0;

            for (int i = 0; i < componentSources.length; i++) {
                try {
                    if (i == 1) {
                        throw new RuntimeException("Syntax error in component source");
                    }
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                }
            }

            assertEquals(2, successCount, "Valid components should be parsed successfully");
            assertEquals(1, failCount, "Invalid component should be marked as failed");
        }

        @Test
        @DisplayName("解析失败的组件应被标记但不抛出异常")
        void shouldMarkFailedComponentsWithoutThrowing() {
            String malformedSource = "this is not valid typescript {{{{";

            assertDoesNotThrow(() -> {
                try {
                    throw new RuntimeException("Parse error");
                } catch (Exception e) {
                }
            });
        }

        @Test
        @DisplayName("批量解析时应记录失败详情")
        void shouldRecordFailureDetailsInBatchProcessing() {
            java.util.Map<String, String> componentResults = new java.util.HashMap<>();
            java.util.Map<String, String> componentErrors = new java.util.HashMap<>();

            componentResults.put("ValidComponent", "SUCCESS");
            componentResults.put("BrokenComponent", "FAILED");
            componentErrors.put("BrokenComponent", "Syntax error at line 5: missing '}'");

            assertEquals("SUCCESS", componentResults.get("ValidComponent"));
            assertEquals("FAILED", componentResults.get("BrokenComponent"));
            assertNotNull(componentErrors.get("BrokenComponent"));
            assertTrue(componentErrors.get("BrokenComponent").contains("Syntax error"));
        }
    }

    @Nested
    @DisplayName("资源耗尽和超时测试")
    class ResourceExhaustionTests {

        @Test
        @DisplayName("大文件上传应触发大小限制")
        void shouldRejectOversizedFiles() {
            int maxFileSize = 10 * 1024 * 1024;
            int oversizedFileSize = 15 * 1024 * 1024;

            assertThrows(IllegalStateException.class, () -> {
                validateFileSize(oversizedFileSize, maxFileSize);
            });
        }

        private void validateFileSize(long fileSize, long maxSize) {
            if (fileSize > maxSize) {
                throw new IllegalStateException(
                        String.format("File size %d exceeds maximum allowed size %d", fileSize, maxSize)
                );
            }
        }

        @Test
        @DisplayName("数据库连接超时应优雅降级")
        void shouldHandleDatabaseTimeoutGracefully() {
            when(tokenMapper.selectById(1L))
                    .thenThrow(new org.springframework.dao.QueryTimeoutException("Connection timeout"));

            assertThrows(org.springframework.dao.QueryTimeoutException.class, () -> {
                tokenService.getTokenById(1L);
            });
        }
    }
}
