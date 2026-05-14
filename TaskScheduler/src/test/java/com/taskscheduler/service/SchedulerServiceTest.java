package com.taskscheduler.service;

import com.taskscheduler.TestDataBuilder;
import com.taskscheduler.entity.ExecuteRecord;
import com.taskscheduler.entity.TaskConfig;
import com.taskscheduler.repository.TaskConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("调度模块 - 并行化测试")
class SchedulerServiceTest {

    @Mock
    private org.quartz.Scheduler quartzScheduler;

    @Mock
    private TaskConfigRepository taskConfigRepository;

    @Mock
    private DispatcherService dispatcherService;

    @InjectMocks
    private SchedulerService schedulerService;

    private TaskConfig testTask;

    @BeforeEach
    void setUp() {
        testTask = TestDataBuilder.createTaskConfig("test_scheduler_task");
    }

    @Test
    @DisplayName("测试任务调度 - Cron表达式正确解析")
    void testScheduleTaskWithCron() throws SchedulerException {
        TaskConfig task = TestDataBuilder.createTaskConfig("cron_task");
        task.setCronExpression("0 0 2 * * ?");

        when(quartzScheduler.checkExists(any(JobKey.class))).thenReturn(false);
        when(quartzScheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
                .thenReturn(new Date());

        schedulerService.scheduleTask(task);

        verify(quartzScheduler).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    @DisplayName("测试任务调度 - 无Cron表达式时跳过")
    void testScheduleTaskWithoutCron() throws SchedulerException {
        TaskConfig task = TestDataBuilder.createTaskConfig("no_cron_task");
        task.setCronExpression(null);

        schedulerService.scheduleTask(task);

        verify(quartzScheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    @DisplayName("测试任务调度 - 已存在任务先删除再重新调度")
    void testRescheduleExistingTask() throws SchedulerException {
        TaskConfig task = TestDataBuilder.createTaskConfig("reschedule_task");
        task.setCronExpression("0 */5 * * * ?");

        when(quartzScheduler.checkExists(any(JobKey.class))).thenReturn(true);
        when(quartzScheduler.deleteJob(any(JobKey.class))).thenReturn(true);
        when(quartzScheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
                .thenReturn(new Date());

        schedulerService.scheduleTask(task);

        verify(quartzScheduler).deleteJob(any(JobKey.class));
        verify(quartzScheduler).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    @DisplayName("测试取消任务调度")
    void testUnscheduleTask() throws SchedulerException {
        String taskId = "to_unschedule";

        when(quartzScheduler.checkExists(any(TriggerKey.class))).thenReturn(true);
        when(quartzScheduler.unscheduleJob(any(TriggerKey.class))).thenReturn(true);
        when(quartzScheduler.checkExists(any(JobKey.class))).thenReturn(true);
        when(quartzScheduler.deleteJob(any(JobKey.class))).thenReturn(true);

        schedulerService.unscheduleTask(taskId);

        verify(quartzScheduler).unscheduleJob(any(TriggerKey.class));
        verify(quartzScheduler).deleteJob(any(JobKey.class));
    }

    @Test
    @DisplayName("测试取消任务调度 - 任务不存在时不报错")
    void testUnscheduleNonExistingTask() throws SchedulerException {
        String taskId = "non_existing";

        when(quartzScheduler.checkExists(any(TriggerKey.class))).thenReturn(false);
        when(quartzScheduler.checkExists(any(JobKey.class))).thenReturn(false);

        schedulerService.unscheduleTask(taskId);

        verify(quartzScheduler, never()).unscheduleJob(any(TriggerKey.class));
        verify(quartzScheduler, never()).deleteJob(any(JobKey.class));
    }

    @Test
    @DisplayName("测试手动触发任务")
    void testTriggerTask() {
        String taskId = "trigger_test_task";
        ExecuteRecord expectedRecord = TestDataBuilder.createExecuteRecord(
                TestDataBuilder.generateExecuteId(), taskId, "pending");

        when(dispatcherService.triggerAndDispatch(taskId, "manual")).thenReturn(expectedRecord);

        schedulerService.triggerTask(taskId, "manual");

        verify(dispatcherService).triggerAndDispatch(taskId, "manual");
    }

    @Test
    @DisplayName("测试多任务并行调度分发")
    void testParallelTaskDispatch() throws Exception {
        int taskCount = 20;
        List<TaskConfig> tasks = new ArrayList<>();
        Map<String, AtomicInteger> dispatchCounter = new ConcurrentHashMap<>();

        for (int i = 0; i < taskCount; i++) {
            String taskId = "parallel_task_" + i;
            tasks.add(TestDataBuilder.createTaskConfig(taskId));
            dispatchCounter.put(taskId, new AtomicInteger(0));
        }

        when(dispatcherService.triggerAndDispatch(anyString(), anyString())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(0);
            dispatchCounter.get(taskId).incrementAndGet();
            return TestDataBuilder.createExecuteRecord(
                    TestDataBuilder.generateExecuteId(), taskId, "running");
        });

        ExecutorService executorService = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(taskCount);

        for (TaskConfig task : tasks) {
            executorService.submit(() -> {
                try {
                    schedulerService.triggerTask(task.getTaskId(), "test");
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "所有任务应在30秒内完成分发");
        executorService.shutdown();

        for (TaskConfig task : tasks) {
            assertEquals(1, dispatchCounter.get(task.getTaskId()).get(),
                    "任务 " + task.getTaskId() + " 应该被分发一次");
        }

        verify(dispatcherService, times(taskCount)).triggerAndDispatch(anyString(), anyString());
    }

    @Test
    @DisplayName("测试并行度控制 - 相同任务不超过最大并发数")
    void testMaxConcurrentControl() throws Exception {
        String taskId = "concurrent_limit_task";
        TaskConfig task = TestDataBuilder.createTaskConfig(taskId);
        task.setMaxConcurrent(3);

        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(10);
        CountDownLatch endLatch = new CountDownLatch(10);

        when(dispatcherService.triggerAndDispatch(anyString(), anyString())).thenAnswer(invocation -> {
            int current = concurrentCount.incrementAndGet();
            maxConcurrent.set(Math.max(maxConcurrent.get(), current));
            
            Thread.sleep(100);
            
            concurrentCount.decrementAndGet();
            endLatch.countDown();
            return TestDataBuilder.createExecuteRecord(
                    TestDataBuilder.generateExecuteId(), taskId, "running");
        });

        ExecutorService executorService = Executors.newFixedThreadPool(10);

        for (int i = 0; i < 10; i++) {
            executorService.submit(() -> {
                try {
                    schedulerService.triggerTask(taskId, "test");
                } finally {
                    startLatch.countDown();
                }
            });
        }

        assertTrue(startLatch.await(30, TimeUnit.SECONDS));
        assertTrue(endLatch.await(30, TimeUnit.SECONDS));
        executorService.shutdown();

        assertTrue(maxConcurrent.get() <= 10, "并发数不应超过线程池大小");
    }

    @Test
    @DisplayName("测试并行调度时的线程安全性")
    void testThreadSafetyInParallelScheduling() throws Exception {
        int threadCount = 15;
        int operationsPerThread = 50;
        String taskId = "thread_safe_task";

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        when(dispatcherService.triggerAndDispatch(anyString(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(10);
            return TestDataBuilder.createExecuteRecord(
                    TestDataBuilder.generateExecuteId(), taskId, "running");
        });

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount * operationsPerThread);

        for (int i = 0; i < threadCount; i++) {
            for (int j = 0; j < operationsPerThread; j++) {
                executorService.submit(() -> {
                    try {
                        schedulerService.triggerTask(taskId, "parallel_test");
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS), "所有操作应在60秒内完成");
        executorService.shutdown();

        assertEquals(0, errorCount.get(), "不应有任何错误");
        assertEquals(threadCount * operationsPerThread, successCount.get(),
                "所有任务应该成功触发");
        verify(dispatcherService, times(threadCount * operationsPerThread))
                .triggerAndDispatch(anyString(), anyString());
    }

    @Test
    @DisplayName("测试调度器异常处理")
    void testSchedulerExceptionHandling() throws SchedulerException {
        TaskConfig task = TestDataBuilder.createTaskConfig("error_task");
        task.setCronExpression("invalid cron");

        when(quartzScheduler.checkExists(any(JobKey.class))).thenReturn(false);
        when(quartzScheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
                .thenThrow(new SchedulerException("Cron表达式解析失败"));

        assertThrows(RuntimeException.class, () -> {
            schedulerService.scheduleTask(task);
        });
    }

    @Test
    @DisplayName("测试时区设置")
    void testTimezoneConfiguration() throws SchedulerException {
        TaskConfig task = TestDataBuilder.createTaskConfig("timezone_task");
        task.setCronExpression("0 0 9 * * ?");
        task.setTimezone("America/New_York");

        when(quartzScheduler.checkExists(any(JobKey.class))).thenReturn(false);
        when(quartzScheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
                .thenReturn(new Date());

        schedulerService.scheduleTask(task);

        verify(quartzScheduler).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    @DisplayName("测试任务禁用时跳过调度")
    void testDisabledTaskNotScheduled() {
        TaskConfig task = TestDataBuilder.createTaskConfig("disabled_task");
        task.setCronExpression("0 * * * * ?");
        task.setEnabled(false);

        schedulerService.scheduleTask(task);

        verify(quartzScheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    @DisplayName("测试调度器启动")
    void testSchedulerStartup() throws SchedulerException {
        SchedulerContext context = mock(SchedulerContext.class);
        when(quartzScheduler.getContext()).thenReturn(context);
        when(quartzScheduler.isStarted()).thenReturn(false);

        schedulerService.init();

        verify(quartzScheduler).start();
    }

    @Test
    @DisplayName("测试批量任务调度")
    void testBatchTaskScheduling() throws SchedulerException {
        List<TaskConfig> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            TaskConfig task = TestDataBuilder.createTaskConfig("batch_task_" + i);
            task.setCronExpression("0 */" + (i + 1) + " * * * ?");
            tasks.add(task);
        }

        when(quartzScheduler.checkExists(any(JobKey.class))).thenReturn(false);
        when(quartzScheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
                .thenReturn(new Date());

        for (TaskConfig task : tasks) {
            schedulerService.scheduleTask(task);
        }

        verify(quartzScheduler, times(5)).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    @DisplayName("测试并发场景下的数据一致性")
    void testDataConsistencyUnderConcurrency() throws Exception {
        int concurrentTasks = 10;
        CountDownLatch readyLatch = new CountDownLatch(concurrentTasks);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentTasks);

        List<String> dispatchedTasks = Collections.synchronizedList(new ArrayList<>());

        when(dispatcherService.triggerAndDispatch(anyString(), anyString())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(0);
            dispatchedTasks.add(taskId);
            return TestDataBuilder.createExecuteRecord(
                    TestDataBuilder.generateExecuteId(), taskId, "running");
        });

        ExecutorService executorService = Executors.newFixedThreadPool(concurrentTasks);

        for (int i = 0; i < concurrentTasks; i++) {
            final String taskId = "consistency_task_" + i;
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    schedulerService.triggerTask(taskId, "concurrent");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        assertEquals(concurrentTasks, dispatchedTasks.size());
        
        Set<String> uniqueTasks = new HashSet<>(dispatchedTasks);
        assertEquals(concurrentTasks, uniqueTasks.size(),
                "每个任务应该只被调度一次");
    }

    @Test
    @DisplayName("测试调度失败时的重试机制")
    void testScheduleFailureRetry() throws SchedulerException {
        TaskConfig task = TestDataBuilder.createTaskConfig("retry_task");
        task.setCronExpression("0 * * * * ?");

        when(quartzScheduler.checkExists(any(JobKey.class))).thenReturn(false);
        when(quartzScheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
                .thenThrow(new SchedulerException("临时错误"));

        assertThrows(RuntimeException.class, () -> {
            schedulerService.scheduleTask(task);
        });
    }
}
