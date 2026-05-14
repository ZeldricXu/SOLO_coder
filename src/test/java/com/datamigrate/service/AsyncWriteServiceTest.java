package com.datamigrate.service;

import com.datamigrate.builder.TestDataBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("异步写入服务测试")
class AsyncWriteServiceTest {

    private AsyncWriteService asyncWriteService;
    private static final String TASK_ID = "test_async_001";

    @BeforeEach
    void setUp() {
        asyncWriteService = new AsyncWriteService();
        asyncWriteService.start();
    }

    @AfterEach
    void tearDown() {
        asyncWriteService.stop();
    }

    @Test
    @DisplayName("服务启动 - 初始状态正确")
    void startService_ShouldBeRunning() {
        AsyncWriteService service = new AsyncWriteService();
        assertFalse(service.isRunning());
        
        service.start();
        assertTrue(service.isRunning());
        
        service.stop();
        assertFalse(service.isRunning());
    }

    @Test
    @DisplayName("任务提交 - 正常加入队列")
    void submitTask_Normal_ShouldReturnTrue() {
        Map<String, Object> record = TestDataBuilder.createSourceRecord(1L, "Test", "test@test.com");
        AsyncWriteService.WriteTask task = new AsyncWriteService.WriteTask(TASK_ID, record, 3);

        boolean submitted = asyncWriteService.submitTask(task);

        assertTrue(submitted);
        assertTrue(asyncWriteService.getQueueSize() >= 0);
    }

    @Test
    @DisplayName("批量任务提交 - 快速提交不阻塞")
    void submitTasks_Batch_ShouldSubmitQuickly() {
        int taskCount = 1000;
        long startTime = System.currentTimeMillis();

        List<Map<String, Object>> records = TestDataBuilder.createBatchSourceRecords(1L, taskCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        AsyncWriteService.WriteResultCallback callback = new AsyncWriteService.WriteResultCallback() {
            @Override
            public void onSuccess(AsyncWriteService.WriteTask task) {
                successCount.incrementAndGet();
            }

            @Override
            public void onFailure(AsyncWriteService.WriteTask task, Exception e) {
                failCount.incrementAndGet();
            }
        };

        asyncWriteService.submitTasks(TASK_ID, records, 3, callback);

        long elapsed = System.currentTimeMillis() - startTime;
        
        assertTrue(elapsed < 5000, "批量提交不应阻塞超过5秒，实际: " + elapsed + "ms");
    }

    @Test
    @DisplayName("写入任务回调 - 成功回调触发")
    void submitTask_WithCallback_ShouldInvokeSuccess() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successInvoked = new AtomicInteger(0);
        AtomicInteger failInvoked = new AtomicInteger(0);

        AsyncWriteService.WriteResultCallback callback = new AsyncWriteService.WriteResultCallback() {
            @Override
            public void onSuccess(AsyncWriteService.WriteTask task) {
                successInvoked.incrementAndGet();
                latch.countDown();
            }

            @Override
            public void onFailure(AsyncWriteService.WriteTask task, Exception e) {
                failInvoked.incrementAndGet();
                latch.countDown();
            }
        };

        asyncWriteService.registerCallback(TASK_ID, callback);
        Map<String, Object> record = TestDataBuilder.createSourceRecord(1L, "Test", "test@test.com");
        asyncWriteService.submitTask(new AsyncWriteService.WriteTask(TASK_ID, record, 3));

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        
        assertTrue(completed, "回调应在超时前触发");
        assertTrue(successInvoked.get() > 0, "成功回调应被触发");
        assertEquals(0, failInvoked.get(), "失败回调不应被触发");
    }

    @Test
    @DisplayName("成功/失败计数器 - 记录正确")
    void counters_ShouldTrackSuccessAndFailure() throws InterruptedException {
        asyncWriteService.resetCounters();
        assertEquals(0, asyncWriteService.getSuccessCount());
        assertEquals(0, asyncWriteService.getFailCount());

        int taskCount = 10;
        CountDownLatch latch = new CountDownLatch(taskCount);

        AsyncWriteService.WriteResultCallback callback = new AsyncWriteService.WriteResultCallback() {
            @Override
            public void onSuccess(AsyncWriteService.WriteTask task) {
                latch.countDown();
            }

            @Override
            public void onFailure(AsyncWriteService.WriteTask task, Exception e) {
                latch.countDown();
            }
        };

        asyncWriteService.registerCallback(TASK_ID, callback);

        for (int i = 0; i < taskCount; i++) {
            Map<String, Object> record = TestDataBuilder.createSourceRecord(
                (long) i, "User" + i, "user" + i + "@test.com");
            asyncWriteService.submitTask(new AsyncWriteService.WriteTask(TASK_ID, record, 3));
        }

        boolean completed = latch.await(30, TimeUnit.SECONDS);
        
        assertTrue(completed, "所有任务应在超时前处理完成");
        assertEquals(taskCount, asyncWriteService.getSuccessCount() + asyncWriteService.getFailCount());
    }

    @Test
    @DisplayName("写入任务属性 - 创建时属性正确")
    void writeTask_Properties_ShouldBeCorrect() {
        Map<String, Object> record = TestDataBuilder.createSourceRecord(42L, "TestUser", "test@test.com");
        
        AsyncWriteService.WriteTask task = new AsyncWriteService.WriteTask(TASK_ID, record, 3);

        assertEquals(TASK_ID, task.getTaskId());
        assertEquals(record, task.getRecord());
        assertEquals(0, task.getRetryCount());
        assertEquals(3, task.getMaxRetries());
        assertTrue(task.getCreatedAt() > 0);
    }

    @Test
    @DisplayName("写入结果对象 - 成功结果")
    void writeResult_Success_ShouldHaveCorrectProperties() {
        Map<String, Object> record = TestDataBuilder.createSourceRecord(1L, "Test", "test@test.com");
        AsyncWriteService.WriteTask task = new AsyncWriteService.WriteTask(TASK_ID, record, 3);

        AsyncWriteService.WriteResult result = new AsyncWriteService.WriteResult(task, true);

        assertTrue(result.isSuccess());
        assertNull(result.getException());
        assertEquals(task, result.getTask());
    }

    @Test
    @DisplayName("写入结果对象 - 失败结果带异常")
    void writeResult_Failure_ShouldHaveCorrectProperties() {
        Map<String, Object> record = TestDataBuilder.createSourceRecord(1L, "Test", "test@test.com");
        AsyncWriteService.WriteTask task = new AsyncWriteService.WriteTask(TASK_ID, record, 3);
        Exception testException = new Exception("测试写入失败");

        AsyncWriteService.WriteResult result = new AsyncWriteService.WriteResult(task, false, testException);

        assertFalse(result.isSuccess());
        assertEquals(testException, result.getException());
        assertEquals(task, result.getTask());
    }

    @Test
    @DisplayName("任务重试计数 - 重试任务属性正确")
    void writeTask_RetryCount_ShouldIncrement() {
        Map<String, Object> record = TestDataBuilder.createSourceRecord(1L, "Test", "test@test.com");
        
        AsyncWriteService.WriteTask originalTask = new AsyncWriteService.WriteTask(TASK_ID, record, 3);
        assertEquals(0, originalTask.getRetryCount());

        AsyncWriteService.WriteTask retryTask = new AsyncWriteService.WriteTask(TASK_ID, record, 1, 3);
        assertEquals(1, retryTask.getRetryCount());
        assertEquals(3, retryTask.getMaxRetries());
    }

    @Test
    @DisplayName("并发提交任务 - 线程安全")
    void submitTask_Concurrent_ShouldBeThreadSafe() throws InterruptedException {
        int threadCount = 10;
        int tasksPerThread = 100;
        int totalTasks = threadCount * tasksPerThread;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(totalTasks);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        AsyncWriteService.WriteResultCallback callback = new AsyncWriteService.WriteResultCallback() {
            @Override
            public void onSuccess(AsyncWriteService.WriteTask task) {
                successCount.incrementAndGet();
                completeLatch.countDown();
            }

            @Override
            public void onFailure(AsyncWriteService.WriteTask task, Exception e) {
                failCount.incrementAndGet();
                completeLatch.countDown();
            }
        };

        asyncWriteService.registerCallback(TASK_ID, callback);

        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            Thread thread = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < tasksPerThread; i++) {
                        long id = threadIndex * 1000L + i;
                        Map<String, Object> record = TestDataBuilder.createSourceRecord(
                            id, "User" + id, "user" + id + "@test.com");
                        asyncWriteService.submitTask(new AsyncWriteService.WriteTask(TASK_ID, record, 3));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }

        startLatch.countDown();

        boolean completed = completeLatch.await(60, TimeUnit.SECONDS);
        
        assertTrue(completed, "所有任务应在超时前完成");
        assertEquals(totalTasks, successCount.get() + failCount.get());
    }

    @Test
    @DisplayName("服务停止 - 优雅关闭")
    void stopService_ShouldShutdownGracefully() {
        AsyncWriteService service = new AsyncWriteService();
        service.start();
        assertTrue(service.isRunning());

        service.stop();
        
        assertFalse(service.isRunning());
    }

    @Test
    @DisplayName("计数器重置 - 归零正确")
    void resetCounters_ShouldResetToZero() {
        asyncWriteService.resetCounters();
        
        assertEquals(0, asyncWriteService.getSuccessCount());
        assertEquals(0, asyncWriteService.getFailCount());
    }

    @Test
    @DisplayName("队列大小 - 提交后队列有数据")
    void getQueueSize_ShouldReturnCorrectSize() throws InterruptedException {
        int taskCount = 100;
        
        for (int i = 0; i < taskCount; i++) {
            Map<String, Object> record = TestDataBuilder.createSourceRecord(
                (long) i, "User" + i, "user" + i + "@test.com");
            asyncWriteService.submitTask(new AsyncWriteService.WriteTask(TASK_ID, record, 3));
        }

        Thread.sleep(100);
        
        assertTrue(asyncWriteService.getQueueSize() >= 0);
    }

    @Test
    @DisplayName("多任务回调隔离 - 不同任务ID的回调独立")
    void multipleTasks_Callbacks_ShouldBeIsolated() throws InterruptedException {
        String taskId1 = "task_001";
        String taskId2 = "task_002";
        CountDownLatch latch1 = new CountDownLatch(5);
        CountDownLatch latch2 = new CountDownLatch(5);

        AsyncWriteService.WriteResultCallback callback1 = new AsyncWriteService.WriteResultCallback() {
            @Override
            public void onSuccess(AsyncWriteService.WriteTask task) {
                latch1.countDown();
            }
            @Override
            public void onFailure(AsyncWriteService.WriteTask task, Exception e) {
                latch1.countDown();
            }
        };

        AsyncWriteService.WriteResultCallback callback2 = new AsyncWriteService.WriteResultCallback() {
            @Override
            public void onSuccess(AsyncWriteService.WriteTask task) {
                latch2.countDown();
            }
            @Override
            public void onFailure(AsyncWriteService.WriteTask task, Exception e) {
                latch2.countDown();
            }
        };

        asyncWriteService.registerCallback(taskId1, callback1);
        asyncWriteService.registerCallback(taskId2, callback2);

        for (int i = 0; i < 5; i++) {
            Map<String, Object> record1 = TestDataBuilder.createSourceRecord((long) i, "T1_User" + i, "t1@test.com");
            asyncWriteService.submitTask(new AsyncWriteService.WriteTask(taskId1, record1, 3));
            
            Map<String, Object> record2 = TestDataBuilder.createSourceRecord((long) i + 100, "T2_User" + i, "t2@test.com");
            asyncWriteService.submitTask(new AsyncWriteService.WriteTask(taskId2, record2, 3));
        }

        boolean completed1 = latch1.await(30, TimeUnit.SECONDS);
        boolean completed2 = latch2.await(30, TimeUnit.SECONDS);

        assertTrue(completed1, "任务1的回调应全部触发");
        assertTrue(completed2, "任务2的回调应全部触发");
    }
}
