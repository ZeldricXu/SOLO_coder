package com.solocoder.storage;

import com.solocoder.base.TestConstants;
import com.solocoder.base.TestDataFactory;
import com.solocoder.domain.model.CoreEntity;
import com.solocoder.infrastructure.adapter.storage.LocalStorageAdapter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class LocalStorageAdapterTest {

    @TempDir
    Path tempDir;

    private LocalStorageAdapter storageAdapter;

    @BeforeEach
    void setUp() throws IOException {
        storageAdapter = new LocalStorageAdapter();
        storageAdapter.setBasePath(tempDir.toString());
        storageAdapter.init();
    }

    @Nested
    @DisplayName("边界条件测试 - storeFile")
    class StoreFileBoundaryTests {

        @Test
        @DisplayName("正常文件存储成功")
        void storeFile_Success() {
            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());

            Mono<String> result = storageAdapter.storeFile(
                    TestConstants.TEST_FILE_NAME,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            );

            StepVerifier.create(result)
                    .assertNext(fileId -> {
                        assertNotNull(fileId);
                        assertTrue(fileId.startsWith("file_"));
                    })
                    .verifyComplete();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("空文件名处理")
        void storeFile_EmptyFileName(String fileName) {
            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());

            Mono<String> result = storageAdapter.storeFile(
                    fileName,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            );

            StepVerifier.create(result)
                    .assertNext(fileId -> assertNotNull(fileId))
                    .verifyComplete();
        }

        @Test
        @DisplayName("超长文件名")
        void storeFile_VeryLongFileName() {
            String longFileName = TestConstants.VERY_LONG_STRING + ".txt";
            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());

            Mono<String> result = storageAdapter.storeFile(
                    longFileName,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            );

            StepVerifier.create(result)
                    .assertNext(fileId -> assertNotNull(fileId))
                    .verifyComplete();
        }

        @Test
        @DisplayName("特殊字符文件名")
        void storeFile_SpecialCharsFileName() {
            String specialFileName = "file" + TestConstants.SPECIAL_CHARS_STRING + ".txt";
            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());

            Mono<String> result = storageAdapter.storeFile(
                    specialFileName,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            );

            StepVerifier.create(result)
                    .assertNext(fileId -> assertNotNull(fileId))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Unicode文件名")
        void storeFile_UnicodeFileName() {
            String unicodeFileName = TestConstants.UNICODE_STRING + ".txt";
            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());

            Mono<String> result = storageAdapter.storeFile(
                    unicodeFileName,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            );

            StepVerifier.create(result)
                    .assertNext(fileId -> assertNotNull(fileId))
                    .verifyComplete();
        }

        @Test
        @DisplayName("路径遍历攻击文件名")
        void storeFile_PathTraversalFileName() {
            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());

            Mono<String> result = storageAdapter.storeFile(
                    TestConstants.PATH_TRAVERSAL_STRING,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            );

            StepVerifier.create(result)
                    .assertNext(fileId -> assertNotNull(fileId))
                    .verifyComplete();
        }

        @Test
        @DisplayName("空内容文件")
        void storeFile_EmptyContent() {
            InputStream content = new ByteArrayInputStream(new byte[0]);

            Mono<String> result = storageAdapter.storeFile(
                    TestConstants.TEST_FILE_NAME,
                    content,
                    0L,
                    TestConstants.TEST_METADATA
            );

            StepVerifier.create(result)
                    .assertNext(fileId -> assertNotNull(fileId))
                    .verifyComplete();
        }

        @Test
        @DisplayName("超大内容文件")
        void storeFile_LargeContent() {
            byte[] largeContent = new byte[1024 * 1024];
            new Random().nextBytes(largeContent);
            InputStream content = new ByteArrayInputStream(largeContent);

            Mono<String> result = storageAdapter.storeFile(
                    TestConstants.TEST_FILE_NAME,
                    content,
                    largeContent.length,
                    TestConstants.TEST_METADATA
            );

            StepVerifier.create(result)
                    .assertNext(fileId -> assertNotNull(fileId))
                    .verifyComplete();
        }

        @Test
        @DisplayName("null元数据")
        void storeFile_NullMetadata() {
            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());

            Mono<String> result = storageAdapter.storeFile(
                    TestConstants.TEST_FILE_NAME,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    null
            );

            StepVerifier.create(result)
                    .assertNext(fileId -> assertNotNull(fileId))
                    .verifyComplete();
        }

        @Test
        @DisplayName("空元数据Map")
        void storeFile_EmptyMetadata() {
            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());

            Mono<String> result = storageAdapter.storeFile(
                    TestConstants.TEST_FILE_NAME,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    Collections.emptyMap()
            );

            StepVerifier.create(result)
                    .assertNext(fileId -> assertNotNull(fileId))
                    .verifyComplete();
        }

        @Test
        @DisplayName("超多元数据条目")
        void storeFile_LargeMetadata() {
            Map<String, String> largeMetadata = new HashMap<>();
            for (int i = 0; i < 1000; i++) {
                largeMetadata.put("key_" + i, "value_" + i);
            }
            InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());

            Mono<String> result = storageAdapter.storeFile(
                    TestConstants.TEST_FILE_NAME,
                    content,
                    TestConstants.TEST_FILE_SIZE,
                    largeMetadata
            );

            StepVerifier.create(result)
                    .assertNext(fileId -> assertNotNull(fileId))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("边界条件测试 - retrieveFile")
    class RetrieveFileBoundaryTests {

        @Test
        @DisplayName("获取存在的文件")
        void retrieveFile_Exists() throws IOException {
            String fileId = storeTestFile();

            Mono<InputStream> result = storageAdapter.retrieveFile(fileId);

            StepVerifier.create(result)
                    .assertNext(inputStream -> {
                        assertNotNull(inputStream);
                        try {
                            byte[] content = inputStream.readAllBytes();
                            assertEquals(TestConstants.TEST_CONTENT, new String(content));
                        } catch (IOException e) {
                            fail("Failed to read content", e);
                        }
                    })
                    .verifyComplete();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "nonexistent_file_id"})
        @DisplayName("获取不存在的文件返回空")
        void retrieveFile_NotFound(String fileId) {
            Mono<InputStream> result = storageAdapter.retrieveFile(fileId);

            StepVerifier.create(result)
                    .expectNextCount(0)
                    .verifyComplete();
        }

        @Test
        @DisplayName("超长fileId查询")
        void retrieveFile_VeryLongFileId() {
            Mono<InputStream> result = storageAdapter.retrieveFile(TestConstants.VERY_LONG_STRING);

            StepVerifier.create(result)
                    .expectNextCount(0)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("边界条件测试 - deleteFile")
    class DeleteFileBoundaryTests {

        @Test
        @DisplayName("删除存在的文件")
        void deleteFile_Exists() throws IOException {
            String fileId = storeTestFile();

            Mono<Boolean> result = storageAdapter.deleteFile(fileId);

            StepVerifier.create(result)
                    .expectNext(true)
                    .verifyComplete();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "nonexistent_file_id"})
        @DisplayName("删除不存在的文件")
        void deleteFile_NotFound(String fileId) {
            Mono<Boolean> result = storageAdapter.deleteFile(fileId);

            StepVerifier.create(result)
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("重复删除同一文件")
        void deleteFile_DoubleDelete() throws IOException {
            String fileId = storeTestFile();

            StepVerifier.create(storageAdapter.deleteFile(fileId))
                    .expectNext(true)
                    .verifyComplete();

            StepVerifier.create(storageAdapter.deleteFile(fileId))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("边界条件测试 - getFileMetadata")
    class GetFileMetadataBoundaryTests {

        @Test
        @DisplayName("获取存在文件的元数据")
        void getFileMetadata_Exists() throws IOException {
            String fileId = storeTestFile();

            Mono<CoreEntity> result = storageAdapter.getFileMetadata(fileId);

            StepVerifier.create(result)
                    .assertNext(metadata -> {
                        assertNotNull(metadata);
                        assertEquals(fileId, metadata.getId());
                        assertEquals("file", metadata.getType());
                        assertEquals("active", metadata.getStatus());
                        assertNotNull(metadata.getCreatedAt());
                        assertNotNull(metadata.getUpdatedAt());
                    })
                    .verifyComplete();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "nonexistent_file_id"})
        @DisplayName("获取不存在文件的元数据返回空")
        void getFileMetadata_NotFound(String fileId) {
            Mono<CoreEntity> result = storageAdapter.getFileMetadata(fileId);

            StepVerifier.create(result)
                    .expectNextCount(0)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("边界条件测试 - listFiles")
    class ListFilesBoundaryTests {

        @Test
        @DisplayName("列出所有文件")
        void listFiles_All() throws IOException {
            for (int i = 0; i < 5; i++) {
                storeTestFileWithName("test_" + i + ".txt");
            }

            Flux<CoreEntity> result = storageAdapter.listFiles(null, 1, 10);

            StepVerifier.create(result.collectList())
                    .assertNext(files -> assertThat(files).hasSize(5))
                    .verifyComplete();
        }

        @Test
        @DisplayName("分页 - 第一页")
        void listFiles_FirstPage() throws IOException {
            for (int i = 0; i < 10; i++) {
                storeTestFileWithName("test_" + i + ".txt");
            }

            Flux<CoreEntity> result = storageAdapter.listFiles(null, 1, 5);

            StepVerifier.create(result.collectList())
                    .assertNext(files -> assertThat(files).hasSize(5))
                    .verifyComplete();
        }

        @Test
        @DisplayName("分页 - 超出范围返回空")
        void listFiles_OutOfRange() throws IOException {
            for (int i = 0; i < 5; i++) {
                storeTestFileWithName("test_" + i + ".txt");
            }

            Flux<CoreEntity> result = storageAdapter.listFiles(null, 10, 5);

            StepVerifier.create(result.collectList())
                    .assertNext(files -> assertThat(files).isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("空目录返回空列表")
        void listFiles_EmptyDirectory() {
            Flux<CoreEntity> result = storageAdapter.listFiles(null, 1, 10);

            StepVerifier.create(result.collectList())
                    .assertNext(files -> assertThat(files).isEmpty())
                    .verifyComplete();
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        @DisplayName("无效页码处理")
        void listFiles_InvalidPageNumber(int page) {
            Flux<CoreEntity> result = storageAdapter.listFiles(null, page, 10);

            StepVerifier.create(result.collectList())
                    .assertNext(files -> assertNotNull(files))
                    .verifyComplete();
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        @DisplayName("无效页大小处理")
        void listFiles_InvalidPageSize(int size) throws IOException {
            storeTestFile();

            Flux<CoreEntity> result = storageAdapter.listFiles(null, 1, size);

            StepVerifier.create(result.collectList())
                    .assertNext(files -> assertNotNull(files))
                    .verifyComplete();
        }

        @Test
        @DisplayName("超大页大小")
        void listFiles_VeryLargePageSize() throws IOException {
            for (int i = 0; i < 5; i++) {
                storeTestFileWithName("test_" + i + ".txt");
            }

            Flux<CoreEntity> result = storageAdapter.listFiles(null, 1, Integer.MAX_VALUE);

            StepVerifier.create(result.collectList())
                    .assertNext(files -> assertThat(files).hasSize(5))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("边界条件测试 - findExpiredFiles")
    class FindExpiredFilesBoundaryTests {

        @Test
        @DisplayName("查找过期文件")
        void findExpiredFiles_HasExpired() throws IOException {
            String expiredFileId = storeTestFile();
            makeFileExpired(expiredFileId);

            Instant expirationTime = Instant.now().minus(30, ChronoUnit.DAYS);
            Flux<CoreEntity> result = storageAdapter.findExpiredFiles(expirationTime);

            StepVerifier.create(result.collectList())
                    .assertNext(files -> assertThat(files).isNotEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("没有过期文件")
        void findExpiredFiles_NoneExpired() throws IOException {
            storeTestFile();

            Instant expirationTime = Instant.now().minus(365, ChronoUnit.DAYS);
            Flux<CoreEntity> result = storageAdapter.findExpiredFiles(expirationTime);

            StepVerifier.create(result.collectList())
                    .assertNext(files -> assertThat(files).isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("null时间参数")
        void findExpiredFiles_NullTime() {
            Flux<CoreEntity> result = storageAdapter.findExpiredFiles(null);

            StepVerifier.create(result)
                    .expectError(NullPointerException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("边界条件测试 - archiveFile")
    class ArchiveFileBoundaryTests {

        @Test
        @DisplayName("归档存在的文件")
        void archiveFile_Exists() throws IOException {
            String fileId = storeTestFile();

            Mono<Void> result = storageAdapter.archiveFile(fileId, "GLACIER");

            StepVerifier.create(result)
                    .verifyComplete();

            StepVerifier.create(storageAdapter.getFileMetadata(fileId))
                    .assertNext(metadata -> assertEquals("archived", metadata.getStatus()))
                    .verifyComplete();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "nonexistent_file_id"})
        @DisplayName("归档不存在的文件")
        void archiveFile_NotFound(String fileId) {
            Mono<Void> result = storageAdapter.archiveFile(fileId, "GLACIER");

            StepVerifier.create(result)
                    .verifyComplete();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("归档存储级别参数边界")
        void archiveFile_InvalidStorageClass(String storageClass) throws IOException {
            String fileId = storeTestFile();

            Mono<Void> result = storageAdapter.archiveFile(fileId, storageClass);

            StepVerifier.create(result)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("边界条件测试 - applyLifecyclePolicy")
    class ApplyLifecyclePolicyBoundaryTests {

        @Test
        @DisplayName("应用策略到存在的文件")
        void applyLifecyclePolicy_Exists() throws IOException {
            String fileId = storeTestFile();

            Mono<Void> result = storageAdapter.applyLifecyclePolicy(fileId, TestConstants.TEST_POLICY_NAME);

            StepVerifier.create(result)
                    .verifyComplete();

            StepVerifier.create(storageAdapter.getFileMetadata(fileId))
                    .assertNext(metadata -> {
                        assertEquals(TestConstants.TEST_POLICY_NAME,
                                metadata.getAttributes().get("lifecyclePolicy"));
                    })
                    .verifyComplete();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("应用策略到不存在的文件")
        void applyLifecyclePolicy_NotFound(String fileId) {
            Mono<Void> result = storageAdapter.applyLifecyclePolicy(fileId, TestConstants.TEST_POLICY_NAME);

            StepVerifier.create(result)
                    .verifyComplete();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("策略名称参数边界")
        void applyLifecyclePolicy_InvalidPolicyName(String policyName) throws IOException {
            String fileId = storeTestFile();

            Mono<Void> result = storageAdapter.applyLifecyclePolicy(fileId, policyName);

            StepVerifier.create(result)
                    .verifyComplete();
        }
    }

    private String storeTestFile() throws IOException {
        InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());
        return storageAdapter.storeFile(
                TestConstants.TEST_FILE_NAME,
                content,
                TestConstants.TEST_FILE_SIZE,
                TestConstants.TEST_METADATA
        ).block();
    }

    private String storeTestFileWithName(String fileName) throws IOException {
        InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());
        return storageAdapter.storeFile(
                fileName,
                content,
                TestConstants.TEST_FILE_SIZE,
                TestConstants.TEST_METADATA
        ).block();
    }

    private void makeFileExpired(String fileId) throws IOException {
        Path metaPath = tempDir.resolve(fileId + ".meta");
        if (Files.exists(metaPath)) {
            String content = Files.readString(metaPath);
            String expiredTime = Instant.now().minus(100, ChronoUnit.DAYS).toString();
            String[] lines = content.split("\n");
            if (lines.length >= 4) {
                lines[3] = expiredTime;
                Files.writeString(metaPath, String.join("\n", lines));
            }
        }
    }
}
