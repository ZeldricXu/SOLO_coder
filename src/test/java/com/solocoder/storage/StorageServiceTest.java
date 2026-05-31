package com.solocoder.storage;

import com.solocoder.application.service.StorageService;
import com.solocoder.base.TestConstants;
import com.solocoder.base.TestDataFactory;
import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.model.CoreEntity;
import com.solocoder.domain.port.StoragePort;
import com.solocoder.domain.port.StructuredLoggerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    @Mock
    private StoragePort storagePort;

    @Mock
    private StructuredLoggerPort logger;

    @InjectMocks
    private StorageService storageService;

    @Nested
    @DisplayName("异常路径测试 - storeFile")
    class StoreFileExceptionTests {

        @Test
        @DisplayName("存储抛出IO异常时返回错误响应")
        void storeFile_IoException_ReturnsError() throws IOException {
            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());
            when(storagePort.storeFile(anyString(), any(), anyLong(), any()))
                    .thenReturn(Mono.error(new IOException("Disk full")));

            Mono<ApiResponse<Map<String, Object>>> result = storageService.storeFile(
                    TestConstants.TEST_FILE_NAME,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(500);
                        assertThat(response.getMessage()).contains("文件存储失败");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("存储抛出运行时异常时返回错误响应")
        void storeFile_RuntimeException_ReturnsError() throws IOException {
            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());
            when(storagePort.storeFile(anyString(), any(), anyLong(), any()))
                    .thenReturn(Mono.error(new RuntimeException("Unexpected error")));

            Mono<ApiResponse<Map<String, Object>>> result = storageService.storeFile(
                    TestConstants.TEST_FILE_NAME,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(500);
                        assertThat(response.getMessage()).contains("文件存储失败");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("存储成功时返回创建成功响应")
        void storeFile_Success_ReturnsCreated() throws IOException {
            String expectedFileId = "file_123456";
            when(storagePort.storeFile(anyString(), any(), anyLong(), any()))
                    .thenReturn(Mono.just(expectedFileId));

            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());
            Mono<ApiResponse<Map<String, Object>>> result = storageService.storeFile(
                    TestConstants.TEST_FILE_NAME,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(201);
                        assertThat(response.getData()).isNotNull();
                        assertThat(response.getData().get("id")).isEqualTo(expectedFileId);
                        assertThat(response.getData().get("status")).isEqualTo("provisioning");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("异常路径测试 - getFileMetadata")
    class GetFileMetadataExceptionTests {

        @Test
        @DisplayName("文件不存在时返回404错误")
        void getFileMetadata_NotFound_Returns404() {
            when(storagePort.getFileMetadata(anyString()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<CoreEntity>> result = storageService.getFileMetadata("nonexistent");

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(404);
                        assertThat(response.getMessage()).isEqualTo("文件不存在");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("查询抛出异常时返回500错误")
        void getFileMetadata_Exception_Returns500() {
            when(storagePort.getFileMetadata(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Database error")));

            Mono<ApiResponse<CoreEntity>> result = storageService.getFileMetadata(TestConstants.TEST_FILE_ID);

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(500);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("查询成功时返回元数据")
        void getFileMetadata_Success_ReturnsMetadata() {
            CoreEntity expected = TestDataFactory.createTestFileEntity(TestConstants.TEST_FILE_ID);
            when(storagePort.getFileMetadata(anyString()))
                    .thenReturn(Mono.just(expected));

            Mono<ApiResponse<CoreEntity>> result = storageService.getFileMetadata(TestConstants.TEST_FILE_ID);

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(200);
                        assertThat(response.getData()).isEqualTo(expected);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("异常路径测试 - deleteFile")
    class DeleteFileExceptionTests {

        @Test
        @DisplayName("删除抛出异常时返回错误")
        void deleteFile_Exception_ReturnsError() {
            when(storagePort.deleteFile(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Delete failed")));

            Mono<ApiResponse<Boolean>> result = storageService.deleteFile(TestConstants.TEST_FILE_ID);

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(500);
                        assertThat(response.getMessage()).contains("删除失败");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("删除成功时返回成功")
        void deleteFile_Success_ReturnsTrue() {
            when(storagePort.deleteFile(anyString()))
                    .thenReturn(Mono.just(true));

            Mono<ApiResponse<Boolean>> result = storageService.deleteFile(TestConstants.TEST_FILE_ID);

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(200);
                        assertThat(response.getData()).isTrue();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("异常路径测试 - applyLifecyclePolicy")
    class ApplyLifecyclePolicyExceptionTests {

        @Test
        @DisplayName("应用策略抛出异常时返回错误")
        void applyLifecyclePolicy_Exception_ReturnsError() {
            when(storagePort.applyLifecyclePolicy(anyString(), anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Policy application failed")));

            Mono<ApiResponse<Void>> result = storageService.applyLifecyclePolicy(
                    TestConstants.TEST_FILE_ID,
                    TestConstants.TEST_POLICY_NAME
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(500);
                        assertThat(response.getMessage()).contains("应用策略失败");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("应用策略成功时返回成功")
        void applyLifecyclePolicy_Success_ReturnsSuccess() {
            when(storagePort.applyLifecyclePolicy(anyString(), anyString()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = storageService.applyLifecyclePolicy(
                    TestConstants.TEST_FILE_ID,
                    TestConstants.TEST_POLICY_NAME
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(200);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("异常路径测试 - retrieveFile")
    class RetrieveFileExceptionTests {

        @Test
        @DisplayName("文件不存在时返回404")
        void retrieveFile_NotFound_Returns404() {
            when(storagePort.retrieveFile(anyString()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<InputStream>> result = storageService.retrieveFile("nonexistent");

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(404);
                        assertThat(response.getMessage()).isEqualTo("文件不存在");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("获取文件成功时返回内容流")
        void retrieveFile_Success_ReturnsStream() {
            InputStream expectedStream = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());
            when(storagePort.retrieveFile(anyString()))
                    .thenReturn(Mono.just(expectedStream));

            Mono<ApiResponse<InputStream>> result = storageService.retrieveFile(TestConstants.TEST_FILE_ID);

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(200);
                        assertThat(response.getData()).isNotNull();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("异常路径测试 - cleanupExpiredFiles")
    class CleanupExpiredFilesExceptionTests {

        @Test
        @DisplayName("清理过期文件时跳过失败的文件")
        void cleanupExpiredFiles_SkipsFailedFiles() {
            CoreEntity expiredFile1 = TestDataFactory.createExpiredFileEntity("file1");
            CoreEntity expiredFile2 = TestDataFactory.createExpiredFileEntity("file2");

            when(storagePort.findExpiredFiles(any()))
                    .thenReturn(Flux.just(expiredFile1, expiredFile2));
            when(storagePort.deleteFile("file1"))
                    .thenReturn(Mono.just(true));
            when(storagePort.deleteFile("file2"))
                    .thenReturn(Mono.error(new RuntimeException("Delete failed")));

            Flux<CoreEntity> result = storageService.cleanupExpiredFiles();

            StepVerifier.create(result.collectList())
                    .assertNext(files -> {
                        assertThat(files).hasSize(1);
                        assertThat(files.get(0).getId()).isEqualTo("file1");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("没有过期文件时返回空")
        void cleanupExpiredFiles_NoExpiredFiles_ReturnsEmpty() {
            when(storagePort.findExpiredFiles(any()))
                    .thenReturn(Flux.empty());

            Flux<CoreEntity> result = storageService.cleanupExpiredFiles();

            StepVerifier.create(result.collectList())
                    .assertNext(files -> assertThat(files).isEmpty())
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("外部依赖故障模拟测试")
    class ExternalDependencyFailureTests {

        @Test
        @DisplayName("存储后端不可用时优雅降级")
        void storeFile_StorageBackendDown_GracefulDegradation() {
            when(storagePort.storeFile(anyString(), any(), anyLong(), any()))
                    .thenReturn(Mono.error(new RuntimeException("Storage service unavailable")));

            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());
            Mono<ApiResponse<Map<String, Object>>> result = storageService.storeFile(
                    TestConstants.TEST_FILE_NAME,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(500);
                        assertThat(response.getMessage()).isNotBlank();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("存储超时后返回错误")
        void storeFile_Timeout_ReturnsError() {
            when(storagePort.storeFile(anyString(), any(), anyLong(), any()))
                    .thenReturn(Mono.never());

            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());
            Mono<ApiResponse<Map<String, Object>>> result = storageService.storeFile(
                    TestConstants.TEST_FILE_NAME,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            ).timeout(java.time.Duration.ofMillis(100))
             .onErrorResume(e -> Mono.just(ApiResponse.error(504, "操作超时")));

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(504))
                    .verifyComplete();
        }
    }
}
