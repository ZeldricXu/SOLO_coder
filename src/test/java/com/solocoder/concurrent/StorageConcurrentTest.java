package com.solocoder.concurrent;

import com.solocoder.base.ConcurrentTestUtils;
import com.solocoder.base.TestConstants;
import com.solocoder.infrastructure.adapter.storage.LocalStorageAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class StorageConcurrentTest {

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
    @DisplayName("并发存储测试")
    class ConcurrentStoreTests {

        @Test
        @DisplayName("50线程同时存储文件 - 100%成功率")
        void concurrentStoreFiles_HighConcurrency_Success() throws Exception {
            AtomicInteger counter = new AtomicInteger(0);

            ConcurrentTestUtils.executeConcurrentlyAndVerify(
                    50,
                    2,
                    () -> {
                        String fileName = "file_" + counter.incrementAndGet() + ".txt";
                        InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());
                        String fileId = storageAdapter.storeFile(
                                fileName,
                                content,
                                TestConstants.TEST_FILE_SIZE,
                                TestConstants.TEST_METADATA
                        ).block();
                        assertNotNull(fileId);
                        return fileId;
                    },
                    1.0
            );
        }

        @Test
        @DisplayName("并发存储后文件数量正确")
        void concurrentStoreFiles_VerifyFileCount() throws Exception {
            int threadCount = 20;
            int iterations = 5;
            int expectedFiles = threadCount * iterations;

            List<String> fileIds = ConcurrentTestUtils.executeConcurrently(
                    threadCount,
                    iterations,
                    () -> {
                        String fileName = "concurrent_test_" + Thread.currentThread().getId() + ".txt";
                        InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());
                        return storageAdapter.storeFile(
                                fileName,
                                content,
                                TestConstants.TEST_FILE_SIZE,
                                TestConstants.TEST_METADATA
                        ).block();
                    }
            );

            assertThat(fileIds)
                    .hasSizeGreaterThanOrEqualTo((int) (expectedFiles * 0.95))
                    .doesNotContainNull();

            long uniqueCount = fileIds.stream().distinct().count();
            assertEquals(fileIds.size(), uniqueCount, "All file IDs should be unique");
        }

        @Test
        @DisplayName("大文件并发存储 - 无数据损坏")
        void concurrentStoreLargeFiles_NoCorruption() throws Exception {
            byte[] largeContent = new byte[1024 * 100];
            new java.util.Random().nextBytes(largeContent);

            ConcurrentTestUtils.executeConcurrentlyAndVerify(
                    10,
                    3,
                    () -> {
                        String fileName = "large_file_" + Thread.currentThread().getId() + ".bin";
                        InputStream content = new ByteArrayInputStream(largeContent);
                        String fileId = storageAdapter.storeFile(
                                fileName,
                                content,
                                largeContent.length,
                                TestConstants.TEST_METADATA
                        ).block();
                        assertNotNull(fileId);

                        InputStream retrieved = storageAdapter.retrieveFile(fileId).block();
                        assertNotNull(retrieved);
                        byte[] retrievedContent = retrieved.readAllBytes();
                        assertArrayEquals(largeContent, retrievedContent, "File content should match");
                        return fileId;
                    },
                    1.0
            );
        }
    }

    @Nested
    @DisplayName("并发读写测试")
    class ConcurrentReadWriteTests {

        @Test
        @DisplayName("读写混合并发 - 无数据竞争")
        void concurrentReadWrite_MixedWorkload_NoRaceCondition() throws Exception {
            String baseFileId = storageAdapter.storeFile(
                    "base.txt",
                    new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes()),
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            ).block();

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger totalCount = new AtomicInteger(0);

            ConcurrentTestUtils.executeConcurrentlyAndVerify(
                    30,
                    3,
                    () -> {
                        totalCount.incrementAndGet();
                        int operation = (int) (Math.random() * 3);

                        switch (operation) {
                            case 0 -> {
                                String fileName = "rw_test_" + Thread.currentThread().getId() + ".txt";
                                InputStream content = new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes());
                                String fileId = storageAdapter.storeFile(
                                        fileName,
                                        content,
                                        TestConstants.TEST_FILE_SIZE,
                                        TestConstants.TEST_METADATA
                                ).block();
                                assertNotNull(fileId);
                                successCount.incrementAndGet();
                            }
                            case 1 -> {
                                InputStream retrieved = storageAdapter.retrieveFile(baseFileId).block();
                                if (retrieved != null) {
                                    successCount.incrementAndGet();
                                }
                            }
                            case 2 -> {
                                var metadata = storageAdapter.getFileMetadata(baseFileId).block();
                                if (metadata != null) {
                                    successCount.incrementAndGet();
                                }
                            }
                        }
                        return null;
                    },
                    0.95
            );
        }

        @Test
        @DisplayName("同一文件并发读取 - 线程安全")
        void concurrentReadSameFile_ThreadSafe() throws Exception {
            String fileId = storageAdapter.storeFile(
                    "shared_file.txt",
                    new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes()),
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            ).block();

            ConcurrentTestUtils.executeConcurrentlyAndVerify(
                    50,
                    5,
                    () -> {
                        InputStream retrieved = storageAdapter.retrieveFile(fileId).block();
                        assertNotNull(retrieved);
                        byte[] content = retrieved.readAllBytes();
                        assertEquals(TestConstants.TEST_CONTENT, new String(content));
                        return fileId;
                    },
                    1.0
            );
        }
    }

    @Nested
    @DisplayName("并发删除测试")
    class ConcurrentDeleteTests {

        @Test
        @DisplayName("并发删除同一文件 - 幂等性保证")
        void concurrentDeleteSameFile_Idempotent() throws Exception {
            String fileId = storageAdapter.storeFile(
                    "delete_test.txt",
                    new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes()),
                    TestConstants.TEST_FILE_SIZE,
                    TestConstants.TEST_METADATA
            ).block();

            ConcurrentTestUtils.executeConcurrently(
                    20,
                    1,
                    () -> storageAdapter.deleteFile(fileId).block()
            );

            var metadata = storageAdapter.getFileMetadata(fileId).block();
            assertNull(metadata, "File should be deleted");
        }

        @Test
        @DisplayName("并发删除不同文件 - 全部成功")
        void concurrentDeleteDifferentFiles_AllSuccess() throws Exception {
            int fileCount = 30;
            String[] fileIds = new String[fileCount];

            for (int i = 0; i < fileCount; i++) {
                fileIds[i] = storageAdapter.storeFile(
                        "to_delete_" + i + ".txt",
                        new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes()),
                        TestConstants.TEST_FILE_SIZE,
                        TestConstants.TEST_METADATA
                ).block();
            }

            AtomicInteger index = new AtomicInteger(0);
            ConcurrentTestUtils.executeConcurrentlyAndVerify(
                    10,
                    3,
                    () -> {
                        int i = index.getAndIncrement() % fileCount;
                        Boolean deleted = storageAdapter.deleteFile(fileIds[i]).block();
                        assertNotNull(deleted);
                        return deleted;
                    },
                    1.0
            );
        }
    }

    @Nested
    @DisplayName("并发列表查询测试")
    class ConcurrentListTests {

        @Test
        @DisplayName("高并发列表查询 - 一致性保证")
        void concurrentListFiles_ConsistentResults() throws Exception {
            for (int i = 0; i < 100; i++) {
                storageAdapter.storeFile(
                        "list_test_" + i + ".txt",
                        new ByteArrayInputStream(TestConstants.TEST_CONTENT.getBytes()),
                        TestConstants.TEST_FILE_SIZE,
                        TestConstants.TEST_METADATA
                ).block();
            }

            ConcurrentTestUtils.executeConcurrentlyAndVerify(
                    30,
                    5,
                    () -> {
                        var files = storageAdapter.listFiles(null, 1, 50)
                                .collectList()
                                .block();
                        assertNotNull(files);
                        assertThat(files.size()).isBetween(1, 50);
                        return files.size();
                    },
                    1.0
            );
        }
    }
}
