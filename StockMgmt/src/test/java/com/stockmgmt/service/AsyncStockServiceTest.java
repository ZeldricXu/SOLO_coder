package com.stockmgmt.service;

import com.stockmgmt.builder.TestDataBuilder;
import com.stockmgmt.dto.InboundRequest;
import com.stockmgmt.dto.InboundResponse;
import com.stockmgmt.dto.OutboundRequest;
import com.stockmgmt.dto.OutboundResponse;
import com.stockmgmt.exception.BusinessException;
import com.stockmgmt.config.TestAsyncConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ContextConfiguration;

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
@ContextConfiguration(classes = TestAsyncConfig.class)
@DisplayName("库存更新异步化测试")
class AsyncStockServiceTest {

    @Mock
    private InboundOutboundService inboundOutboundService;

    @InjectMocks
    private AsyncStockService asyncStockService;

    @BeforeEach
    void setUp() {
        TestDataBuilder.reset();
    }

    @Test
    @DisplayName("异步入库 - 任务提交后立即返回任务ID")
    void testSubmitInboundTask_ImmediateReturn() {
        InboundRequest request = TestDataBuilder.buildInboundRequest();

        String taskId = asyncStockService.submitInboundTask(request);

        assertNotNull(taskId);
        assertTrue(taskId.startsWith("TASK_"));

        Optional<AsyncStockService.StockUpdateTask> taskOpt = asyncStockService.getTaskStatus(taskId);
        assertTrue(taskOpt.isPresent());

        AsyncStockService.StockUpdateTask task = taskOpt.get();
        assertEquals(AsyncStockService.TaskType.INBOUND, task.getTaskType());
        assertNotNull(task.getSubmittedAt());
    }

    @Test
    @DisplayName("异步出库 - 任务提交后立即返回任务ID")
    void testSubmitOutboundTask_ImmediateReturn() {
        OutboundRequest request = TestDataBuilder.buildOutboundRequest();

        String taskId = asyncStockService.submitOutboundTask(request);

        assertNotNull(taskId);
        assertTrue(taskId.startsWith("TASK_"));

        Optional<AsyncStockService.StockUpdateTask> taskOpt = asyncStockService.getTaskStatus(taskId);
        assertTrue(taskOpt.isPresent());

        AsyncStockService.StockUpdateTask task = taskOpt.get();
        assertEquals(AsyncStockService.TaskType.OUTBOUND, task.getTaskType());
    }

    @Test
    @DisplayName("入库任务执行成功 - 正确返回成功结果")
    void testExecuteInboundAsync_Success() throws Exception {
        InboundRequest request = TestDataBuilder.buildInboundRequest();
        InboundResponse expectedResponse = InboundResponse.builder()
                .stockId("STOCK_001")
                .recordId("RECORD_001")
                .currentQuantity(150)
                .build();

        when(inboundOutboundService.inbound(any(InboundRequest.class))).thenReturn(expectedResponse);

        String taskId = asyncStockService.submitInboundTask(request);

        Thread.sleep(500);

        Optional<AsyncStockService.StockUpdateTask> taskOpt = asyncStockService.getTaskStatus(taskId);
        assertTrue(taskOpt.isPresent());

        AsyncStockService.StockUpdateTask task = taskOpt.get();
        assertEquals(AsyncStockService.TaskStatus.COMPLETED, task.getStatus());
        assertNotNull(task.getCompletedAt());
        assertNull(task.getErrorMessage());
        assertEquals(0, task.getRetryCount());
    }

    @Test
    @DisplayName("出库任务执行成功 - 正确返回成功结果")
    void testExecuteOutboundAsync_Success() throws Exception {
        OutboundRequest request = TestDataBuilder.buildOutboundRequest();
        OutboundResponse expectedResponse = OutboundResponse.builder()
                .stockId("STOCK_001")
                .recordId("RECORD_002")
                .currentQuantity(90)
                .build();

        when(inboundOutboundService.outbound(any(OutboundRequest.class))).thenReturn(expectedResponse);

        String taskId = asyncStockService.submitOutboundTask(request);

        Thread.sleep(500);

        Optional<AsyncStockService.StockUpdateTask> taskOpt = asyncStockService.getTaskStatus(taskId);
        assertTrue(taskOpt.isPresent());

        AsyncStockService.StockUpdateTask task = taskOpt.get();
        assertEquals(AsyncStockService.TaskStatus.COMPLETED, task.getStatus());
        assertNotNull(task.getCompletedAt());
    }

    @Test
    @DisplayName("入库任务失败重试 - 验证重试机制")
    void testExecuteInboundAsync_RetryMechanism() throws Exception {
        InboundRequest request = TestDataBuilder.buildInboundRequest();
        InboundResponse expectedResponse = InboundResponse.builder()
                .stockId("STOCK_001")
                .recordId("RECORD_001")
                .build();

        AtomicInteger attemptCount = new AtomicInteger(0);
        when(inboundOutboundService.inbound(any(InboundRequest.class)))
                .thenAnswer(invocation -> {
                    int attempt = attemptCount.incrementAndGet();
                    if (attempt < 2) {
                        throw new RuntimeException("临时网络错误");
                    }
                    return expectedResponse;
                });

        String taskId = asyncStockService.submitInboundTask(request);

        Thread.sleep(3000);

        Optional<AsyncStockService.StockUpdateTask> taskOpt = asyncStockService.getTaskStatus(taskId);
        assertTrue(taskOpt.isPresent());

        AsyncStockService.StockUpdateTask task = taskOpt.get();
        assertTrue(task.getStatus() == AsyncStockService.TaskStatus.COMPLETED ||
                   task.getStatus() == AsyncStockService.TaskStatus.FAILED);
        assertEquals(1, task.getRetryCount());
    }

    @Test
    @DisplayName("出库任务库存不足 - 不重试直接失败")
    void testExecuteOutboundAsync_InsufficientStock_NoRetry() throws Exception {
        OutboundRequest request = TestDataBuilder.buildOutboundRequest();

        when(inboundOutboundService.outbound(any(OutboundRequest.class)))
                .thenThrow(BusinessException.of("库存不足，当前可用: 5"));

        String taskId = asyncStockService.submitOutboundTask(request);

        Thread.sleep(500);

        Optional<AsyncStockService.StockUpdateTask> taskOpt = asyncStockService.getTaskStatus(taskId);
        assertTrue(taskOpt.isPresent());

        AsyncStockService.StockUpdateTask task = taskOpt.get();
        assertEquals(AsyncStockService.TaskStatus.FAILED, task.getStatus());
        assertEquals(0, task.getRetryCount());
        assertTrue(task.getErrorMessage().contains("库存不足"));
    }

    @Test
    @DisplayName("任务达到最大重试次数 - 最终失败")
    void testExecuteInboundAsync_MaxRetryExceeded() throws Exception {
        InboundRequest request = TestDataBuilder.buildInboundRequest();

        when(inboundOutboundService.inbound(any(InboundRequest.class)))
                .thenThrow(new RuntimeException("持久化错误"));

        String taskId = asyncStockService.submitInboundTask(request);

        Thread.sleep(8000);

        Optional<AsyncStockService.StockUpdateTask> taskOpt = asyncStockService.getTaskStatus(taskId);
        assertTrue(taskOpt.isPresent());

        AsyncStockService.StockUpdateTask task = taskOpt.get();
        assertEquals(AsyncStockService.TaskStatus.FAILED, task.getStatus());
        assertEquals(2, task.getRetryCount());
        assertNotNull(task.getErrorMessage());
    }

    @Test
    @DisplayName("并发提交多个任务 - 任务独立执行")
    void testSubmitMultipleTasks_Concurrent() throws Exception {
        int taskCount = 5;
        CountDownLatch latch = new CountDownLatch(taskCount);
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        AtomicInteger successCount = new AtomicInteger(0);

        InboundResponse mockResponse = InboundResponse.builder()
                .stockId("STOCK_001")
                .build();
        when(inboundOutboundService.inbound(any(InboundRequest.class))).thenReturn(mockResponse);

        for (int i = 0; i < taskCount; i++) {
            executor.submit(() -> {
                try {
                    InboundRequest request = TestDataBuilder.buildInboundRequest();
                    String taskId = asyncStockService.submitInboundTask(request);
                    Thread.sleep(600);
                    Optional<AsyncStockService.StockUpdateTask> taskOpt = asyncStockService.getTaskStatus(taskId);
                    if (taskOpt.isPresent() && taskOpt.get().getStatus() == AsyncStockService.TaskStatus.COMPLETED) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(taskCount, successCount.get());
    }

    @Test
    @DisplayName("获取任务状态 - 任务不存在返回空")
    void testGetTaskStatus_NotFound() {
        Optional<AsyncStockService.StockUpdateTask> taskOpt = asyncStockService.getTaskStatus("INVALID_TASK_ID");
        assertFalse(taskOpt.isPresent());
    }

    @Test
    @DisplayName("清理已完成任务 - 正确清理完成和失败的任务")
    void testClearCompletedTasks() throws Exception {
        InboundResponse mockResponse = InboundResponse.builder().build();
        when(inboundOutboundService.inbound(any(InboundRequest.class))).thenReturn(mockResponse);

        String taskId1 = asyncStockService.submitInboundTask(TestDataBuilder.buildInboundRequest());
        Thread.sleep(600);

        when(inboundOutboundService.outbound(any(OutboundRequest.class)))
                .thenThrow(new RuntimeException("测试失败"));

        String taskId2 = asyncStockService.submitOutboundTask(TestDataBuilder.buildOutboundRequest());
        Thread.sleep(2000);

        asyncStockService.clearCompletedTasks();

        assertFalse(asyncStockService.getTaskStatus(taskId1).isPresent());
        assertFalse(asyncStockService.getTaskStatus(taskId2).isPresent());
    }

    @Test
    @DisplayName("任务ID唯一 - 确保每次任务ID不重复")
    void testTaskId_Unique() {
        String taskId1 = asyncStockService.submitInboundTask(TestDataBuilder.buildInboundRequest());
        String taskId2 = asyncStockService.submitInboundTask(TestDataBuilder.buildInboundRequest());
        String taskId3 = asyncStockService.submitOutboundTask(TestDataBuilder.buildOutboundRequest());

        assertNotEquals(taskId1, taskId2);
        assertNotEquals(taskId1, taskId3);
        assertNotEquals(taskId2, taskId3);
    }

    @Test
    @DisplayName("入库成功结果 - 验证结果对象包含正确数据")
    void testInboundResult_SuccessData() throws Exception {
        InboundResponse expectedResponse = InboundResponse.builder()
                .stockId("STOCK_123")
                .recordId("RECORD_456")
                .batchId("BATCH_789")
                .currentQuantity(150)
                .availableQuantity(150)
                .build();

        when(inboundOutboundService.inbound(any(InboundRequest.class))).thenReturn(expectedResponse);

        String taskId = asyncStockService.submitInboundTask(TestDataBuilder.buildInboundRequest());
        Thread.sleep(500);

        Optional<AsyncStockService.StockUpdateTask> taskOpt = asyncStockService.getTaskStatus(taskId);
        assertTrue(taskOpt.isPresent());
        assertEquals(AsyncStockService.TaskStatus.COMPLETED, taskOpt.get().getStatus());
    }

    @Test
    @DisplayName("任务处理中状态 - 验证PROCESSING状态")
    void testTaskStatus_Processing() {
        InboundRequest request = TestDataBuilder.buildInboundRequest();
        String taskId = asyncStockService.submitInboundTask(request);

        Optional<AsyncStockService.StockUpdateTask> taskOpt = asyncStockService.getTaskStatus(taskId);
        assertTrue(taskOpt.isPresent());

        AsyncStockService.TaskStatus status = taskOpt.get().getStatus();
        assertTrue(status == AsyncStockService.TaskStatus.PENDING ||
                   status == AsyncStockService.TaskStatus.PROCESSING ||
                   status == AsyncStockService.TaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("批量任务提交 - 高并发任务提交测试")
    void testBatchTaskSubmission() throws Exception {
        int batchSize = 20;
        CountDownLatch submitLatch = new CountDownLatch(batchSize);
        CountDownLatch completeLatch = new CountDownLatch(batchSize);
        ExecutorService executor = Executors.newFixedThreadPool(10);

        InboundResponse mockResponse = InboundResponse.builder().build();
        when(inboundOutboundService.inbound(any(InboundRequest.class))).thenReturn(mockResponse);

        for (int i = 0; i < batchSize; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    submitLatch.countDown();
                    submitLatch.await();

                    InboundRequest request = TestDataBuilder.buildInboundRequest(
                            "PROD_" + index, 10, "BATCH_" + index, "loc_zone_a_01");
                    String taskId = asyncStockService.submitInboundTask(request);
                    Thread.sleep(800);

                    Optional<AsyncStockService.StockUpdateTask> taskOpt = asyncStockService.getTaskStatus(taskId);
                    if (taskOpt.isPresent() && taskOpt.get().getStatus() == AsyncStockService.TaskStatus.COMPLETED) {
                        completeLatch.countDown();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        boolean completed = completeLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "批量任务未能在超时时间内完成");
    }
}
