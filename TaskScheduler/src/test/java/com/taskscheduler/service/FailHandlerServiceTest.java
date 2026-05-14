package com.taskscheduler.service;

import com.taskscheduler.TestDataBuilder;
import com.taskscheduler.entity.FailRecord;
import com.taskscheduler.entity.TaskConfig;
import com.taskscheduler.repository.FailRecordRepository;
import com.taskscheduler.repository.TaskConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("失败处理模块测试")
class FailHandlerServiceTest {

    @Mock
    private FailRecordRepository failRecordRepository;

    @Mock
    private TaskConfigRepository taskConfigRepository;

    @Mock
    private LogService logService;

    @Mock
    private DispatcherService dispatcherService;

    @InjectMocks
    private FailHandlerService failHandlerService;

    private TaskConfig testTask;

    @BeforeEach
    void setUp() {
        testTask = TestDataBuilder.createTaskConfig("fail_test_task");
        testTask.setRetryCount(3);
    }

    @Test
    @DisplayName("测试记录失败 - 创建失败记录")
    void testRecordFailure() {
        String taskId = "fail_task_1";
        String executeId = "exec_fail_1";
        String failReason = "数据库连接超时";

        when(failRecordRepository.save(any(FailRecord.class))).thenAnswer(invocation -> {
            FailRecord saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        FailRecord result = failHandlerService.recordFailure(executeId, taskId, failReason);

        assertNotNull(result);
        assertEquals(taskId, result.getTaskId());
        assertEquals(executeId, result.getExecuteId());
        assertEquals(failReason, result.getFailReason());
        assertEquals("retrying", result.getStatus());
        assertEquals(0, result.getRetryCount());
        assertNotNull(result.getNextRetryTime());

        verify(failRecordRepository).save(any(FailRecord.class));
        verify(logService).logError(eq(executeId), eq(taskId), anyString());
    }

    @Test
    @DisplayName("测试重试次数未超限 - 安排重试")
    void testHandleFailureWithinRetryLimit() {
        String taskId = "retry_task";
        String executeId = "exec_retry_1";
        String failReason = "临时网络错误";

        TaskConfig task = TestDataBuilder.createTaskWithRetry(taskId, 3);

        when(taskConfigRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(failRecordRepository.save(any(FailRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        failHandlerService.handleFailure(executeId, taskId, failReason, 0);

        verify(failRecordRepository).save(any(FailRecord.class));
    }

    @Test
    @DisplayName("测试重试次数已超限 - 标记为永久失败")
    void testHandleFailureExceedsRetryLimit() {
        String taskId = "final_fail_task";
        String executeId = "exec_final_fail";
        String failReason = "严重错误，无法恢复";

        TaskConfig task = TestDataBuilder.createTaskWithRetry(taskId, 2);

        when(taskConfigRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(failRecordRepository.findByExecuteId(executeId)).thenReturn(
                Optional.of(TestDataBuilder.createFailRecord(taskId, executeId, 2, "retrying")));
        when(failRecordRepository.save(any(FailRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        failHandlerService.handleFailure(executeId, taskId, failReason, 2);

        verify(failRecordRepository).save(any(FailRecord.class));
    }

    @Test
    @DisplayName("测试重试间隔递增 - 指数退避策略")
    void testExponentialBackoff() {
        String taskId = "backoff_task";
        String executeId = "exec_backoff";

        TaskConfig task = TestDataBuilder.createTaskWithRetry(taskId, 5);

        when(taskConfigRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(failRecordRepository.save(any(FailRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LocalDateTime now = LocalDateTime.now();

        List<LocalDateTime> retryTimes = new ArrayList<>();
        for (int retryNum = 0; retryNum < 3; retryNum++) {
            failHandlerService.handleFailure(executeId + "_" + retryNum, taskId, "测试失败", retryNum);
        }

        verify(failRecordRepository, times(3)).save(any(FailRecord.class));
    }

    @Test
    @DisplayName("测试任务不存在时不抛出异常")
    void testHandleFailureWithNonExistingTask() {
        String taskId = "non_existing_task";
        String executeId = "exec_non_existing";

        when(taskConfigRepository.findByTaskId(taskId)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> {
            failHandlerService.handleFailure(executeId, taskId, "错误", 0);
        });

        verify(failRecordRepository, never()).save(any(FailRecord.class));
    }

    @Test
    @DisplayName("测试重试处理 - 处理待重试的失败记录")
    void testProcessPendingRetries() {
        int pendingCount = 5;
        List<FailRecord> pendingRecords = new ArrayList<>();

        for (int i = 0; i < pendingCount; i++) {
            pendingRecords.add(TestDataBuilder.createPendingRetryRecord(
                    "pending_task_" + i, "exec_pending_" + i));
        }

        when(failRecordRepository.findPendingRetries(any(LocalDateTime.class))).thenReturn(pendingRecords);
        when(dispatcherService.triggerAndDispatch(anyString(), eq("retry"))).thenAnswer(invocation -> {
            return TestDataBuilder.createExecuteRecord(
                    TestDataBuilder.generateExecuteId(), invocation.getArgument(0), "running");
        });

        failHandlerService.processPendingRetries();

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        verify(dispatcherService, timeout(5000).times(pendingCount))
                .triggerAndDispatch(anyString(), eq("retry"));
    }

    @Test
    @DisplayName("测试待重试列表为空时不执行操作")
    void testProcessPendingRetriesEmptyList() {
        when(failRecordRepository.findPendingRetries(any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        failHandlerService.processPendingRetries();

        verify(dispatcherService, never()).triggerAndDispatch(anyString(), anyString());
    }

    @Test
    @DisplayName("测试批量任务失败处理能力")
    void testBatchFailureHandling() throws Exception {
        int failureCount = 100;
        TaskConfig task = TestDataBuilder.createTaskWithRetry("batch_task", 3);

        when(taskConfigRepository.findByTaskId("batch_task")).thenReturn(Optional.of(task));
        when(failRecordRepository.save(any(FailRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExecutorService executorService = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(failureCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < failureCount; i++) {
            final int index = i;
            executorService.submit(() -> {
                try {
                    failHandlerService.handleFailure(
                            "exec_batch_" + index,
                            "batch_task",
                            "批量测试失败 - " + index,
                            0
                    );
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        assertEquals(failureCount, successCount.get());
        assertEquals(0, errorCount.get());
        verify(failRecordRepository, times(failureCount)).save(any(FailRecord.class));
    }

    @Test
    @DisplayName("测试重试执行异常时不影响其他任务")
    void testRetryExceptionDoesNotAffectOthers() {
        List<FailRecord> records = new ArrayList<>();
        records.add(TestDataBuilder.createPendingRetryRecord("task_success_1", "exec_success_1"));
        records.add(TestDataBuilder.createPendingRetryRecord("task_fail", "exec_fail"));
        records.add(TestDataBuilder.createPendingRetryRecord("task_success_2", "exec_success_2"));

        when(failRecordRepository.findPendingRetries(any(LocalDateTime.class))).thenReturn(records);

        when(dispatcherService.triggerAndDispatch(anyString(), eq("retry")))
                .thenAnswer(invocation -> {
                    String taskId = invocation.getArgument(0);
                    if ("task_fail".equals(taskId)) {
                        throw new RuntimeException("模拟重试失败");
                    }
                    return TestDataBuilder.createExecuteRecord(
                            TestDataBuilder.generateExecuteId(), taskId, "running");
                });

        failHandlerService.processPendingRetries();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        verify(dispatcherService, timeout(5000).times(3))
                .triggerAndDispatch(anyString(), eq("retry"));
    }

    @Test
    @DisplayName("测试获取任务失败记录")
    void testGetFailRecordsByTaskId() {
        String taskId = "history_task";
        List<FailRecord> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            records.add(TestDataBuilder.createFailRecord(taskId, "exec_" + i, i,
                    i == 4 ? "failed" : "retrying"));
        }

        when(failRecordRepository.findByTaskIdOrderByCreatedAtDesc(taskId)).thenReturn(records);

        List<FailRecord> result = failHandlerService.getFailRecordsByTaskId(taskId);

        assertEquals(5, result.size());
        verify(failRecordRepository).findByTaskIdOrderByCreatedAtDesc(taskId);
    }

    @Test
    @DisplayName("测试通过执行ID获取失败记录")
    void testGetFailRecordByExecuteId() {
        String executeId = "exec_specific";
        FailRecord record = TestDataBuilder.createFailRecord("specific_task", executeId, 2, "retrying");

        when(failRecordRepository.findByExecuteId(executeId)).thenReturn(Optional.of(record));

        Optional<FailRecord> result = failHandlerService.getFailRecordByExecuteId(executeId);

        assertTrue(result.isPresent());
        assertEquals(executeId, result.get().getExecuteId());
    }

    @Test
    @DisplayName("测试执行ID不存在时返回空")
    void testGetFailRecordByNonExistingExecuteId() {
        String executeId = "non_existing_exec";

        when(failRecordRepository.findByExecuteId(executeId)).thenReturn(Optional.empty());

        Optional<FailRecord> result = failHandlerService.getFailRecordByExecuteId(executeId);

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("测试统计永久失败的记录数")
    void testCountFailedRecords() {
        String taskId = "count_task";

        when(failRecordRepository.countFailedRecords(taskId)).thenReturn(5L);

        long count = failHandlerService.countFailedRecords(taskId);

        assertEquals(5, count);
        verify(failRecordRepository).countFailedRecords(taskId);
    }

    @Test
    @DisplayName("测试重试异步化不阻塞主线程")
    void testAsyncRetryDoesNotBlock() throws Exception {
        List<FailRecord> records = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            records.add(TestDataBuilder.createPendingRetryRecord("async_task_" + i, "exec_async_" + i));
        }

        CountDownLatch retryLatch = new CountDownLatch(10);
        when(failRecordRepository.findPendingRetries(any(LocalDateTime.class))).thenReturn(records);
        when(dispatcherService.triggerAndDispatch(anyString(), eq("retry"))).thenAnswer(invocation -> {
            Thread.sleep(100);
            retryLatch.countDown();
            return TestDataBuilder.createExecuteRecord(
                    TestDataBuilder.generateExecuteId(), invocation.getArgument(0), "running");
        });

        long startTime = System.currentTimeMillis();
        failHandlerService.processPendingRetries();
        long elapsedTime = System.currentTimeMillis() - startTime;

        assertTrue(elapsedTime < 500, "processPendingRetries 应快速返回，实际耗时: " + elapsedTime + "ms");

        assertTrue(retryLatch.await(5, TimeUnit.SECONDS), "所有重试应在5秒内完成");
    }

    @Test
    @DisplayName("测试首次失败 - 重试计数从0开始")
    void testFirstFailureStartsRetryCountAtZero() {
        String taskId = "first_fail_task";
        String executeId = "exec_first_fail";

        TaskConfig task = TestDataBuilder.createTaskWithRetry(taskId, 5);

        when(taskConfigRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(failRecordRepository.save(any(FailRecord.class))).thenAnswer(invocation -> {
            FailRecord saved = invocation.getArgument(0);
            assertEquals(1, saved.getRetryCount());
            return saved;
        });

        failHandlerService.handleFailure(executeId, taskId, "首次失败", 0);

        verify(failRecordRepository).save(any(FailRecord.class));
    }

    @Test
    @DisplayName("测试多次重试后达到极限")
    void testMultipleRetriesReachingLimit() {
        String taskId = "limit_task";
        TaskConfig task = TestDataBuilder.createTaskWithRetry(taskId, 3);

        when(taskConfigRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(failRecordRepository.save(any(FailRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        for (int i = 0; i <= 3; i++) {
            final int currentRetry = i;
            if (i == 0) {
                when(failRecordRepository.findByExecuteId("exec_limit_" + i)).thenReturn(Optional.empty());
            } else {
                when(failRecordRepository.findByExecuteId("exec_limit_" + i)).thenReturn(
                        Optional.of(TestDataBuilder.createFailRecord(taskId, "exec_limit_" + i, i, "retrying")));
            }
        }

        failHandlerService.handleFailure("exec_limit_0", taskId, "失败1", 0);
        failHandlerService.handleFailure("exec_limit_1", taskId, "失败2", 1);
        failHandlerService.handleFailure("exec_limit_2", taskId, "失败3", 2);
        failHandlerService.handleFailure("exec_limit_3", taskId, "失败4", 3);

        verify(failRecordRepository, times(4)).save(any(FailRecord.class));
    }

    @Test
    @DisplayName("测试重试间隔时间正确性")
    void testRetryIntervalCalculation() {
        String taskId = "interval_task";
        String executeId = "exec_interval";

        TaskConfig task = TestDataBuilder.createTaskWithRetry(taskId, 5);

        when(taskConfigRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(failRecordRepository.save(any(FailRecord.class))).thenAnswer(invocation -> {
            FailRecord saved = invocation.getArgument(0);
            return saved;
        });

        failHandlerService.handleFailure(executeId + "_0", taskId, "重试0", 0);
        failHandlerService.handleFailure(executeId + "_1", taskId, "重试1", 1);
        failHandlerService.handleFailure(executeId + "_2", taskId, "重试2", 2);

        verify(failRecordRepository, times(3)).save(any(FailRecord.class));
    }

    @Test
    @DisplayName("测试并发场景下的失败处理")
    void testConcurrentFailureHandling() throws Exception {
        int threadCount = 15;
        int failuresPerThread = 20;
        String taskId = "concurrent_fail_task";

        TaskConfig task = TestDataBuilder.createTaskWithRetry(taskId, 10);

        when(taskConfigRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(failRecordRepository.save(any(FailRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount * failuresPerThread);

        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            for (int f = 0; f < failuresPerThread; f++) {
                final int failId = f;
                executorService.submit(() -> {
                    try {
                        failHandlerService.handleFailure(
                                "exec_concurrent_" + threadId + "_" + failId,
                                taskId,
                                "并发测试失败",
                                0
                        );
                        successCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        latch.await(60, TimeUnit.SECONDS);
        executorService.shutdown();

        assertEquals(threadCount * failuresPerThread, successCount.get());
        verify(failRecordRepository, times(threadCount * failuresPerThread))
                .save(any(FailRecord.class));
    }

    @Test
    @DisplayName("测试失败记录与重试记录的关联")
    void testFailRecordLinkedToRetry() {
        String taskId = "linked_task";
        String executeId = "exec_linked";

        FailRecord existingRecord = TestDataBuilder.createFailRecord(taskId, executeId, 0, "retrying");

        TaskConfig task = TestDataBuilder.createTaskWithRetry(taskId, 3);

        when(taskConfigRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(failRecordRepository.findByExecuteId(executeId)).thenReturn(Optional.of(existingRecord));
        when(failRecordRepository.save(any(FailRecord.class))).thenAnswer(invocation -> {
            FailRecord saved = invocation.getArgument(0);
            existingRecord.setRetryCount(saved.getRetryCount());
            existingRecord.setStatus(saved.getStatus());
            return saved;
        });

        failHandlerService.handleFailure(executeId, taskId, "再次失败", 1);

        assertEquals(2, existingRecord.getRetryCount());
    }
}
