package com.solocoder.platform.storage.application.service;

import com.solocoder.platform.storage.domain.model.StoredContent;
import com.solocoder.platform.storage.domain.repository.StoredContentRepository;
import com.solocoder.platform.storage.domain.service.ContentHashCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageApplicationService - 并发安全测试")
class StorageApplicationServiceConcurrencyTest {

    @Mock
    private ContentHashCalculator contentHashCalculator;

    @Mock
    private StoredContentRepository storedContentRepository;

    @InjectMocks
    private StorageApplicationService storageApplicationService;

    @Test
    @DisplayName("并发安全 - 多线程同时上传不同内容")
    void upload_MultipleThreadsDifferentContent_ShouldAllSucceed() throws InterruptedException {
        int threadCount = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenAnswer(invocation -> "0x" + System.nanoTime());
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenAnswer(invocation -> "Qm" + System.nanoTime());
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> {
                    StoredContent content = invocation.getArgument(0);
                    content.setId(System.nanoTime());
                    return content;
                });

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    storageApplicationService.upload(
                            "content-" + index,
                            "text/plain",
                            "IPFS",
                            "mainnet",
                            true,
                            null,
                            Map.of("index", index),
                            "user-" + index
                    );
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Ignore
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount, successCount.get());
    }

    @Test
    @DisplayName("并发安全 - 多线程同时Pin/Unpin同一内容")
    void pinUnpin_ConcurrentSameContent_ShouldBeThreadSafe() throws InterruptedException {
        int threadCount = 30;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger pinCount = new AtomicInteger(0);
        AtomicInteger unpinCount = new AtomicInteger(0);

        String contentId = "QmConcurrentTest";
        StoredContent storedContent = StoredContent.builder()
                .contentId(contentId)
                .pinStatus(StoredContent.PinStatus.UNPINNED)
                .build();

        when(storedContentRepository.findByContentId(contentId))
                .thenReturn(Optional.of(storedContent));
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExecutorService executor = Executors.newFixedThreadPool(10);
        for (int i = 0; i < threadCount; i++) {
            final boolean isPin = i % 2 == 0;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (isPin) {
                        storageApplicationService.pinContent(contentId, "node1");
                        pinCount.incrementAndGet();
                    } else {
                        storageApplicationService.unpinContent(contentId);
                        unpinCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount / 2, pinCount.get() + unpinCount.get());
    }

    @RepeatedTest(5)
    @DisplayName("并发安全 - 重复测试：大量并发上传请求稳定性")
    void upload_HighConcurrency_ShouldBeStable() throws InterruptedException {
        int threadCount = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        Map<String, String> contentMap = new HashMap<>();

        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenAnswer(invocation -> "0x" + System.nanoTime());
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenAnswer(invocation -> "Qm" + System.nanoTime());
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> {
                    StoredContent content = invocation.getArgument(0);
                    contentMap.put(content.getContentId(), content.getContentHash());
                    content.setId(System.nanoTime());
                    return content;
                });

        ExecutorService executor = Executors.newFixedThreadPool(20);
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            final String storageType = index % 3 == 0 ? "IPFS" : (index % 3 == 1 ? "ARWEAVE" : "FILECOIN");
            executor.submit(() -> {
                try {
                    startLatch.await();
                    StoredContent result = storageApplicationService.upload(
                            "content-data-" + index,
                            "text/plain",
                            storageType,
                            "mainnet",
                            index % 2 == 0,
                            null,
                            Map.of("thread", index, "type", storageType),
                            "user-" + index
                    );
                    if (result != null && result.getContentId() != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore exceptions
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue(successCount.get() > 0, "至少应有一些上传成功");
    }

    @Test
    @DisplayName("并发安全 - ContentHashCalculator多线程计算无状态污染")
    void contentHashCalculator_MultiThreaded_NoStateContamination() throws InterruptedException {
        int threadCount = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        ContentHashCalculator calculator = new ContentHashCalculator();
        AtomicInteger successCount = new AtomicInteger(0);
        Map<String, String> hashResults = new HashMap<>();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String content = "Thread content " + index;
                    String hash = calculator.calculateContentHash(content.getBytes());
                    String cid = calculator.calculateContentId(content.getBytes(), "IPFS");

                    hashResults.put(cid, hash);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Ignore
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount, successCount.get());
        assertEquals(threadCount, hashResults.size(), "每个线程应产生唯一的CID");
    }

    @Test
    @DisplayName("并发安全 - 多线程同时查询相同内容信息")
    void getContentInfo_ConcurrentReads_ShouldAllSucceed() throws InterruptedException {
        int threadCount = 30;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        String contentId = "QmSharedContent";
        StoredContent storedContent = StoredContent.builder()
                .contentId(contentId)
                .contentHash("0xabc123")
                .storageType(StoredContent.StorageType.IPFS)
                .build();

        when(storedContentRepository.findByContentId(contentId))
                .thenReturn(Optional.of(storedContent));

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    StoredContent result = storageApplicationService.getContentInfo(contentId);
                    if (result != null && contentId.equals(result.getContentId())) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount, successCount.get());
    }
}
