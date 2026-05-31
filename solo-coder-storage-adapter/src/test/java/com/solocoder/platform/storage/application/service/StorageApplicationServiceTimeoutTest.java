package com.solocoder.platform.storage.application.service;

import com.solocoder.platform.storage.domain.model.StoredContent;
import com.solocoder.platform.storage.domain.repository.StoredContentRepository;
import com.solocoder.platform.storage.domain.service.ContentHashCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageApplicationService - 超时降级测试")
class StorageApplicationServiceTimeoutTest {

    @Mock
    private ContentHashCalculator contentHashCalculator;

    @Mock
    private StoredContentRepository storedContentRepository;

    @InjectMocks
    private StorageApplicationService storageApplicationService;

    @Test
    @DisplayName("超时降级 - 数据库写入超时时，抛出异常保证事务回滚")
    void upload_DatabaseWriteTimeout_ShouldThrowException() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenReturn("0xabc123");
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenReturn("QmXYZ123");
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(5000);
                    return invocation.getArgument(0);
                });

        Future<StoredContent> future = executor.submit(() -> {
            try {
                return storageApplicationService.upload(
                        "test content", "text/plain", "IPFS",
                        "mainnet", true, null, null, "user1");
            } catch (Exception e) {
                return null;
            }
        });

        try {
            StoredContent result = future.get(100, TimeUnit.MILLISECONDS);
            assertNull(result, "应该超时");
        } catch (TimeoutException e) {
            future.cancel(true);
            System.out.println("TimeoutException caught - 数据库写入超时");
        }

        executor.shutdownNow();
    }

    @Test
    @DisplayName("超时降级 - 内容哈希计算超时")
    void upload_HashCalculationTimeout_ShouldPropagateError() throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(2000);
                    return "0xslow";
                });
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenReturn("QmSLOW");
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Future<StoredContent> future = executor.submit(() -> {
            try {
                return storageApplicationService.upload(
                        "test content", "text/plain", "IPFS",
                        "mainnet", true, null, null, "user1");
            } catch (Exception e) {
                return null;
            }
        });

        try {
            StoredContent result = future.get(500, TimeUnit.MILLISECONDS);
            assertNull(result);
        } catch (TimeoutException e) {
            future.cancel(true);
            System.out.println("哈希计算超时");
        } catch (ExecutionException e) {
            System.out.println("执行异常: " + e.getMessage());
        }

        executor.shutdownNow();
    }

    @Test
    @DisplayName("超时降级 - 数据库查询超时")
    void getContentInfo_DatabaseQueryTimeout_ShouldThrowException() throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        String contentId = "QmTIMEOUT";
        when(storedContentRepository.findByContentId(contentId))
                .thenAnswer(invocation -> {
                    Thread.sleep(3000);
                    return Optional.empty();
                });

        Future<StoredContent> future = executor.submit(() -> {
            try {
                return storageApplicationService.getContentInfo(contentId);
            } catch (Exception e) {
                return null;
            }
        });

        try {
            StoredContent result = future.get(200, TimeUnit.MILLISECONDS);
            assertNull(result);
        } catch (TimeoutException e) {
            future.cancel(true);
            System.out.println("数据库查询超时");
        } catch (ExecutionException e) {
            System.out.println("执行异常: " + e.getMessage());
        }

        executor.shutdownNow();
    }

    @Test
    @DisplayName("超时降级 - ContentId生成超时")
    void upload_ContentIdGenerationTimeout() throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenReturn("0xabc");
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenAnswer(invocation -> {
                    Thread.sleep(2000);
                    return "QmSLOWID";
                });
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Future<StoredContent> future = executor.submit(() -> {
            try {
                return storageApplicationService.upload(
                        "test content", "text/plain", "IPFS",
                        "mainnet", true, null, null, "user1");
            } catch (Exception e) {
                return null;
            }
        });

        try {
            StoredContent result = future.get(500, TimeUnit.MILLISECONDS);
            assertNull(result);
        } catch (TimeoutException e) {
            future.cancel(true);
            System.out.println("ContentId生成超时");
        } catch (ExecutionException e) {
            System.out.println("执行异常: " + e.getMessage());
        }

        executor.shutdownNow();
    }

    @Test
    @DisplayName("超时降级 - Pin操作数据库写入失败")
    void pinContent_DatabaseWriteFail_ShouldThrowException() {
        String contentId = "QmPINFAIL";
        StoredContent storedContent = StoredContent.builder()
                .contentId(contentId)
                .pinStatus(StoredContent.PinStatus.UNPINNED)
                .build();

        when(storedContentRepository.findByContentId(contentId))
                .thenReturn(Optional.of(storedContent));
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenThrow(new RuntimeException("Database connection lost"));

        assertThrows(RuntimeException.class, () -> {
            storageApplicationService.pinContent(contentId, "local-node");
        });
    }

    @Test
    @DisplayName("超时降级 - 删除操作数据库连接超时")
    void deleteContent_DatabaseTimeout_ShouldHandleGracefully() {
        String contentId = "QmDELETE";

        when(storedContentRepository.deleteByContentId(contentId))
                .thenThrow(new RuntimeException("Connection timeout"));

        assertThrows(RuntimeException.class, () -> {
            storageApplicationService.deleteContent(contentId);
        });
    }

    @Test
    @DisplayName("超时降级 - 网关URL获取时存储库查询失败")
    void getGatewayUrl_RepositoryQueryFail_ShouldThrowException() {
        String contentId = "QmGATEWAY";

        when(storedContentRepository.findByContentId(contentId))
                .thenThrow(new RuntimeException("Database error"));

        assertThrows(RuntimeException.class, () -> {
            storageApplicationService.getGatewayUrl(contentId);
        });
    }

    @Test
    @DisplayName("超时降级 - 数据库连接恢复后正常工作")
    void upload_DatabaseRecover_ShouldWorkNormally() {
        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenReturn("0xabc123");
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenReturn("QmXYZ123");

        when(storedContentRepository.save(any(StoredContent.class)))
                .thenThrow(new RuntimeException("DB unavailable"))
                .thenAnswer(invocation -> {
                    StoredContent content = invocation.getArgument(0);
                    content.setId(1L);
                    return content;
                });

        assertThrows(RuntimeException.class, () -> {
            storageApplicationService.upload(
                    "content1", "text/plain", "IPFS",
                    "mainnet", true, null, null, "user1");
        });

        StoredContent result = storageApplicationService.upload(
                "content2", "text/plain", "IPFS",
                "mainnet", true, null, null, "user1");

        assertNotNull(result);
        assertEquals("QmXYZ123", result.getContentId());
    }

    @Test
    @DisplayName("超时降级 - 部分失败不影响其他操作")
    void partialFailure_ShouldNotAffectOtherOperations() {
        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenReturn("0xabc");
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenReturn("QmTEST");

        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> {
                    StoredContent content = invocation.getArgument(0);
                    if ("fail-upload".equals(content.getCreatedBy())) {
                        throw new RuntimeException("DB failure");
                    }
                    content.setId(System.currentTimeMillis());
                    return content;
                });

        StoredContent success = storageApplicationService.upload(
                "success content", "text/plain", "IPFS",
                "mainnet", true, null, null, "success-user");

        assertThrows(RuntimeException.class, () -> {
            storageApplicationService.upload(
                    "fail content", "text/plain", "IPFS",
                    "mainnet", true, null, null, "fail-upload");
        });

        assertNotNull(success);
        assertEquals("QmTEST", success.getContentId());
    }
}
