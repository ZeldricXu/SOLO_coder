package com.assetinventory.service;

import com.assetinventory.builder.TestDataBuilder;
import com.assetinventory.entity.InventoryPerson;
import com.assetinventory.entity.InventoryPlan;
import com.assetinventory.entity.InventoryTask;
import com.assetinventory.exception.InventoryException;
import com.assetinventory.repository.InventoryTaskRepository;
import com.assetinventory.util.TaskLockManager;
import com.assetinventory.util.TaskLockManager.TaskLock;
import com.assetinventory.util.TaskLockManager.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("任务模块单元测试 - 任务锁定机制")
class TaskServiceTest {

    @Mock
    private InventoryTaskRepository taskRepository;

    @Mock
    private PlanService planService;

    @Mock
    private PersonService personService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private TaskService taskService;

    private TaskLockManager lockManager;
    private InventoryTask testTask;
    private InventoryPerson testPerson;
    private InventoryPlan testPlan;

    @BeforeEach
    void setUp() {
        lockManager = new TaskLockManager();
        testTask = TestDataBuilder.taskBuilder().buildPendingTask();
        testPerson = TestDataBuilder.personBuilder().buildActivePerson();
        testPlan = TestDataBuilder.planBuilder().buildActivePlan();
    }

    @Test
    @DisplayName("测试任务分配前获取分布式锁 - 成功获取")
    void testAcquireLock_BeforeAssignment_Success() {
        String taskId = testTask.getTaskId();
        String holder = "worker-001";

        TaskLock lock = lockManager.tryAcquireLock(taskId, holder, TaskPriority.NORMAL);

        assertNotNull(lock);
        assertEquals(taskId, lock.getTaskId());
        assertEquals(holder, lock.getHolder());
        assertEquals(TaskPriority.NORMAL, lock.getPriority());
        assertTrue(lockManager.isLocked(taskId));
    }

    @Test
    @DisplayName("测试任务分配前获取分布式锁 - 锁已被占用")
    void testAcquireLock_AlreadyLocked_ReturnsNull() {
        String taskId = testTask.getTaskId();
        String holder1 = "worker-001";
        String holder2 = "worker-002";

        TaskLock lock1 = lockManager.tryAcquireLock(taskId, holder1, TaskPriority.NORMAL);
        TaskLock lock2 = lockManager.tryAcquireLock(taskId, holder2, TaskPriority.NORMAL);

        assertNotNull(lock1);
        assertNull(lock2);
        assertEquals(holder1, lockManager.getCurrentLock(taskId).getHolder());
    }

    @Test
    @DisplayName("测试紧急盘点短超时 - 5秒超时")
    void testLockTimeout_UrgentPriority_ShortTimeout() {
        String taskId = testTask.getTaskId();
        String holder = "worker-001";

        TaskLock lock = lockManager.tryAcquireLock(taskId, holder, TaskPriority.URGENT);

        assertNotNull(lock);
        assertEquals(TaskPriority.URGENT, lock.getPriority());
        assertEquals(TaskPriority.URGENT.getTimeoutMs(), 5000L);
        assertTrue(lock.getRemainingTime() <= 5000);
        assertTrue(lock.getRemainingTime() > 0);
    }

    @Test
    @DisplayName("测试普通盘点长超时 - 30秒超时")
    void testLockTimeout_NormalPriority_LongTimeout() {
        String taskId = testTask.getTaskId();
        String holder = "worker-001";

        TaskLock lock = lockManager.tryAcquireLock(taskId, holder, TaskPriority.NORMAL);

        assertNotNull(lock);
        assertEquals(TaskPriority.NORMAL, lock.getPriority());
        assertEquals(TaskPriority.NORMAL.getTimeoutMs(), 30000L);
        assertTrue(lock.getRemainingTime() <= 30000);
        assertTrue(lock.getRemainingTime() > 5000);
    }

    @Test
    @DisplayName("测试不同优先级超时差异")
    void testLockTimeout_PriorityDifference() {
        String urgentTaskId = "task_urgent_001";
        String normalTaskId = "task_normal_001";

        TaskLock urgentLock = lockManager.tryAcquireLock(urgentTaskId, "worker-001", TaskPriority.URGENT);
        TaskLock normalLock = lockManager.tryAcquireLock(normalTaskId, "worker-002", TaskPriority.NORMAL);

        assertNotNull(urgentLock);
        assertNotNull(normalLock);
        assertTrue(urgentLock.getPriority().getTimeoutMs() < normalLock.getPriority().getTimeoutMs());
        assertEquals(5000L, urgentLock.getPriority().getTimeoutMs());
        assertEquals(30000L, normalLock.getPriority().getTimeoutMs());
    }

    @Test
    @DisplayName("测试锁定释放 - 主动释放")
    void testLockRelease_ManualRelease() {
        String taskId = testTask.getTaskId();
        String holder = "worker-001";

        TaskLock lock = lockManager.tryAcquireLock(taskId, holder, TaskPriority.NORMAL);
        assertNotNull(lock);
        assertTrue(lockManager.isLocked(taskId));

        boolean released = lockManager.releaseLock(lock);

        assertTrue(released);
        assertTrue(lock.isReleased());
        assertFalse(lockManager.isLocked(taskId));
    }

    @Test
    @DisplayName("测试锁定释放后重新获取")
    void testLockRelease_ReacquireAfterRelease() {
        String taskId = testTask.getTaskId();
        String holder1 = "worker-001";
        String holder2 = "worker-002";

        TaskLock lock1 = lockManager.tryAcquireLock(taskId, holder1, TaskPriority.NORMAL);
        lockManager.releaseLock(lock1);

        TaskLock lock2 = lockManager.tryAcquireLock(taskId, holder2, TaskPriority.NORMAL);

        assertNotNull(lock2);
        assertEquals(holder2, lock2.getHolder());
        assertTrue(lockManager.isLocked(taskId));
    }

    @Test
    @DisplayName("测试锁定恢复 - 超时后自动释放")
    void testLockRecovery_TimeoutAutoRelease() throws InterruptedException {
        String taskId = testTask.getTaskId();
        String holder = "worker-001";

        TaskLockManager shortLockManager = new TaskLockManager() {
            @Override
            public TaskLock tryAcquireLock(String taskId, String holder, TaskPriority priority) {
                return super.tryAcquireLock(taskId, holder, TaskPriority.URGENT);
            }
        };

        TaskLock lock = shortLockManager.tryAcquireLock(taskId, holder, TaskPriority.URGENT);
        assertNotNull(lock);
        assertTrue(shortLockManager.isLocked(taskId));

        Thread.sleep(100);
        assertFalse(lock.isExpired());

        Thread.sleep(5100);

        TaskLock newLock = shortLockManager.tryAcquireLock(taskId, "new-worker", TaskPriority.NORMAL);
        assertNotNull(newLock);
    }

    @Test
    @DisplayName("测试并发分配锁冲突 - 只有一个线程获取锁")
    void testConcurrentAssignment_LockConflict() throws InterruptedException {
        String taskId = "concurrent_task_001";
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int workerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    TaskLock lock = lockManager.tryAcquireLock(
                            taskId,
                            "worker-" + workerId,
                            TaskPriority.NORMAL
                    );
                    if (lock != null) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successCount.get());
        assertEquals(threadCount - 1, failCount.get());
        assertTrue(lockManager.isLocked(taskId));
    }

    @Test
    @DisplayName("测试人员不可用时拒绝处理")
    void testPersonUnavailable_RejectAssignment() {
        when(taskRepository.save(any(InventoryTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(planService).validatePlanActive(anyString());
        when(personService.assignTaskToPerson())
                .thenThrow(new InventoryException(400, "没有可用的盘点人员"));

        InventoryException exception = assertThrows(InventoryException.class,
                () -> taskService.createTask(testPlan.getPlanId(), "测试区域"));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("没有可用的盘点人员"));
        verify(personService, times(1)).assignTaskToPerson();
        verify(statisticsService, never()).incrementTaskCount();
    }

    @Test
    @DisplayName("测试人员任务已满时拒绝分配")
    void testPersonTaskFull_RejectAssignment() {
        when(taskRepository.save(any(InventoryTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(planService).validatePlanActive(anyString());
        when(personService.assignTaskToPerson())
                .thenThrow(new InventoryException(400, "所有人员任务已满"));

        InventoryException exception = assertThrows(InventoryException.class,
                () -> taskService.createTask(testPlan.getPlanId(), "测试区域"));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("任务已满"));
    }

    @Test
    @DisplayName("测试创建任务完整流程 - 成功")
    void testCreateTask_Success() {
        when(taskRepository.save(any(InventoryTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(planService).validatePlanActive(anyString());
        when(personService.assignTaskToPerson()).thenReturn(testPerson);
        when(personService.incrementTaskCount(anyString())).thenReturn(testPerson);
        doNothing().when(historyService).recordTaskHistory(anyString(), anyString(), anyString());

        InventoryTask created = taskService.createTask(testPlan.getPlanId(), "测试区域");

        assertNotNull(created);
        assertEquals("assigned", created.getTaskStatus());
        assertEquals(testPerson.getPersonId(), created.getAssignedPerson());
        assertNotNull(created.getAssignedAt());

        verify(planService, times(1)).validatePlanActive(testPlan.getPlanId());
        verify(personService, times(1)).assignTaskToPerson();
        verify(personService, times(1)).incrementTaskCount(testPerson.getPersonId());
        verify(taskRepository, times(2)).save(any(InventoryTask.class));
        verify(statisticsService, times(1)).incrementTaskCount();
    }

    @Test
    @DisplayName("测试创建任务 - 计划已关闭")
    void testCreateTask_PlanClosed() {
        doThrow(new InventoryException(400, "盘点计划已关闭"))
                .when(planService).validatePlanActive(anyString());

        InventoryException exception = assertThrows(InventoryException.class,
                () -> taskService.createTask(testPlan.getPlanId(), "测试区域"));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("已关闭"));
        verify(taskRepository, never()).save(any(InventoryTask.class));
    }

    @Test
    @DisplayName("测试任务状态验证 - 已完成任务拒绝执行")
    void testValidateTaskStatus_CompletedTask() {
        InventoryTask completedTask = TestDataBuilder.taskBuilder()
                .taskId(testTask.getTaskId())
                .buildCompletedTask(testPerson.getPersonId());

        when(taskRepository.findByTaskId(anyString())).thenReturn(Optional.of(completedTask));

        InventoryException exception = assertThrows(InventoryException.class,
                () -> taskService.validateTaskPendingOrAssigned(testTask.getTaskId()));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("已完成"));
    }

    @Test
    @DisplayName("测试任务状态验证 - 待分配任务可以执行")
    void testValidateTaskStatus_PendingTask() {
        InventoryTask pendingTask = TestDataBuilder.taskBuilder()
                .taskId(testTask.getTaskId())
                .buildPendingTask();

        when(taskRepository.findByTaskId(anyString())).thenReturn(Optional.of(pendingTask));

        assertDoesNotThrow(() -> taskService.validateTaskPendingOrAssigned(testTask.getTaskId()));
    }

    @Test
    @DisplayName("测试任务状态验证 - 已分配任务可以执行")
    void testValidateTaskStatus_AssignedTask() {
        InventoryTask assignedTask = TestDataBuilder.taskBuilder()
                .taskId(testTask.getTaskId())
                .buildAssignedTask(testPerson.getPersonId());

        when(taskRepository.findByTaskId(anyString())).thenReturn(Optional.of(assignedTask));

        assertDoesNotThrow(() -> taskService.validateTaskPendingOrAssigned(testTask.getTaskId()));
    }

    @Test
    @DisplayName("测试获取任务不存在")
    void testGetTaskByIdOrThrow_NotFound() {
        when(taskRepository.findByTaskId(anyString())).thenReturn(Optional.empty());

        InventoryException exception = assertThrows(InventoryException.class,
                () -> taskService.getTaskByIdOrThrow("nonexistent_task"));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("任务不存在"));
    }

    @Test
    @DisplayName("测试按状态获取任务")
    void testGetTasksByStatus() {
        List<InventoryTask> pendingTasks = TestDataBuilder.taskBuilder().buildMultiple(3, "pending");
        List<InventoryTask> assignedTasks = TestDataBuilder.taskBuilder().buildMultiple(2, "assigned");
        List<InventoryTask> completedTasks = TestDataBuilder.taskBuilder().buildMultiple(5, "completed");

        when(taskRepository.findByTaskStatus("pending")).thenReturn(pendingTasks);
        when(taskRepository.findByTaskStatus("assigned")).thenReturn(assignedTasks);
        when(taskRepository.findByTaskStatus("completed")).thenReturn(completedTasks);

        assertEquals(3, taskService.getTasksByStatus("pending").size());
        assertEquals(2, taskService.getTasksByStatus("assigned").size());
        assertEquals(5, taskService.getTasksByStatus("completed").size());
    }

    @Test
    @DisplayName("测试锁管理器清理过期锁")
    void testLockManager_CleanExpiredLocks() {
        String task1 = "task_001";
        String task2 = "task_002";
        String task3 = "task_003";

        lockManager.tryAcquireLock(task1, "worker-001", TaskPriority.URGENT);
        lockManager.tryAcquireLock(task2, "worker-002", TaskPriority.NORMAL);
        lockManager.tryAcquireLock(task3, "worker-003", TaskPriority.URGENT);

        assertEquals(3, lockManager.getActiveLockCount());

        TaskLock lock1 = lockManager.getCurrentLock(task1);
        assertNotNull(lock1);
        lockManager.releaseLock(lock1);

        assertEquals(2, lockManager.getActiveLockCount());
    }

    @Test
    @DisplayName("测试释放非当前持有者的锁")
    void testReleaseLock_NotCurrentHolder() {
        String taskId = testTask.getTaskId();
        TaskLock lock1 = lockManager.tryAcquireLock(taskId, "worker-001", TaskPriority.NORMAL);
        TaskLock fakeLock = new TaskLock(taskId, "fake-worker", TaskPriority.NORMAL);

        boolean released = lockManager.releaseLock(fakeLock);

        assertFalse(released);
        assertTrue(lockManager.isLocked(taskId));
        assertEquals("worker-001", lockManager.getCurrentLock(taskId).getHolder());
    }

    @Test
    @DisplayName("测试清空所有锁")
    void testClearAllLocks() {
        lockManager.tryAcquireLock("task_001", "worker-001", TaskPriority.NORMAL);
        lockManager.tryAcquireLock("task_002", "worker-002", TaskPriority.NORMAL);
        lockManager.tryAcquireLock("task_003", "worker-003", TaskPriority.NORMAL);

        assertEquals(3, lockManager.getActiveLockCount());

        lockManager.clearAllLocks();

        assertEquals(0, lockManager.getActiveLockCount());
    }
}
