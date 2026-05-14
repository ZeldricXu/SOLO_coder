package com.deviceops.service;

import com.deviceops.builder.TestDataBuilder;
import com.deviceops.entity.FaultRecord;
import com.deviceops.entity.OperationTask;
import com.deviceops.entity.Operator;
import com.deviceops.exception.DeviceOpsException;
import com.deviceops.repository.OperationTaskRepository;
import com.deviceops.service.analysis.AnalysisService;
import com.deviceops.service.device.DeviceService;
import com.deviceops.service.fault.FaultService;
import com.deviceops.service.history.HistoryService;
import com.deviceops.service.operator.OperatorService;
import com.deviceops.service.task.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("任务模块测试")
class TaskServiceTest {

    @Mock
    private OperationTaskRepository taskRepository;

    @Mock
    private DeviceService deviceService;

    @Mock
    private OperatorService operatorService;

    @Mock
    private HistoryService historyService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private FaultService faultService;

    @InjectMocks
    private TaskService taskService;

    @Nested
    @DisplayName("任务创建测试")
    class TaskCreationTests {

        @Test
        @DisplayName("任务创建成功 - 从故障记录创建任务")
        void createTaskFromFault_Success() {
            FaultRecord fault = TestDataBuilder.buildPendingFault();
            Operator operator = TestDataBuilder.buildAvailableOperator();

            when(deviceService.exists("device_001")).thenReturn(true);
            when(operatorService.findOptimalOperator("hardware"))
                    .thenReturn(Optional.of(operator));
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            OperationTask result = taskService.createTaskFromFault(fault);

            assertNotNull(result);
            assertEquals("fault_001", result.getFaultId());
            assertEquals("device_001", result.getDeviceId());
            assertEquals("assigned", result.getTaskStatus());
            verify(taskRepository, times(1)).save(any(OperationTask.class));
        }

        @Test
        @DisplayName("任务创建成功 - 无可用人员时状态为pending")
        void createTaskFromFault_NoOperator_StatusPending() {
            FaultRecord fault = TestDataBuilder.buildPendingFault();

            when(deviceService.exists("device_001")).thenReturn(true);
            when(operatorService.findOptimalOperator("hardware"))
                    .thenReturn(Optional.empty());
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            OperationTask result = taskService.createTaskFromFault(fault);

            assertNotNull(result);
            assertEquals("pending", result.getTaskStatus());
            assertNull(result.getOperatorId());
        }

        @Test
        @DisplayName("任务创建 - 设备不存在时抛出异常")
        void createTaskFromFault_DeviceNotExists_ThrowsException() {
            FaultRecord fault = TestDataBuilder.buildPendingFault();
            when(deviceService.exists("device_001")).thenReturn(false);

            DeviceOpsException exception = assertThrows(DeviceOpsException.class, () -> {
                taskService.createTaskFromFault(fault);
            });

            assertEquals(404, exception.getCode());
        }

        @Test
        @DisplayName("创建高优先级任务")
        void createHighPriorityTask_Success() {
            FaultRecord fault = TestDataBuilder.buildHighPriorityFault();

            when(deviceService.exists("device_001")).thenReturn(true);
            when(operatorService.findOptimalOperator("hardware"))
                    .thenReturn(Optional.of(TestDataBuilder.buildHardwareOperator()));
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            OperationTask result = taskService.createHighPriorityTask(fault);

            assertEquals("high", result.getPriorityLevel());
            assertEquals(1800, result.getLockTimeoutSeconds());
        }

        @Test
        @DisplayName("创建中优先级任务")
        void createMediumPriorityTask_Success() {
            FaultRecord fault = TestDataBuilder.buildMediumPriorityFault();

            when(deviceService.exists("device_001")).thenReturn(true);
            when(operatorService.findOptimalOperator("software"))
                    .thenReturn(Optional.of(TestDataBuilder.buildSoftwareOperator()));
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            OperationTask result = taskService.createMediumPriorityTask(fault);

            assertEquals("medium", result.getPriorityLevel());
            assertEquals(3600, result.getLockTimeoutSeconds());
        }

        @Test
        @DisplayName("创建低优先级任务")
        void createLowPriorityTask_Success() {
            FaultRecord fault = TestDataBuilder.buildLowPriorityFault();

            when(deviceService.exists("device_001")).thenReturn(true);
            when(operatorService.findOptimalOperator("network"))
                    .thenReturn(Optional.empty());
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            OperationTask result = taskService.createLowPriorityTask(fault);

            assertEquals("low", result.getPriorityLevel());
            assertEquals(7200, result.getLockTimeoutSeconds());
        }
    }

    @Nested
    @DisplayName("任务锁定机制测试")
    class TaskLockingTests {

        @Test
        @DisplayName("任务锁定成功")
        void lockTask_Success() {
            OperationTask task = TestDataBuilder.buildPendingTask();
            when(taskRepository.findById("task_001")).thenReturn(Optional.of(task));
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            OperationTask result = taskService.lockTask("task_001", "operator_001");

            assertTrue(result.getIsLocked());
            assertEquals("operator_001", result.getLockedBy());
            assertNotNull(result.getLockedAt());
        }

        @Test
        @DisplayName("任务已锁定时 - 再次锁定抛出异常")
        void lockTask_AlreadyLocked_ThrowsException() {
            OperationTask lockedTask = TestDataBuilder.buildLockedTask();
            when(taskRepository.findById("task_001")).thenReturn(Optional.of(lockedTask));

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                taskService.lockTask("task_001", "operator_002");
            });

            assertTrue(exception.getMessage().contains("任务已被锁定"));
        }

        @Test
        @DisplayName("任务锁定已过期时 - 可以重新锁定")
        void lockTask_ExpiredLock_CanRelock() {
            OperationTask expiredTask = TestDataBuilder.buildExpiredLockTask();
            when(taskRepository.findById("task_001")).thenReturn(Optional.of(expiredTask));
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            OperationTask result = taskService.lockTask("task_001", "operator_002");

            assertTrue(result.getIsLocked());
            assertEquals("operator_002", result.getLockedBy());
        }

        @Test
        @DisplayName("任务解锁成功")
        void unlockTask_Success() {
            OperationTask lockedTask = TestDataBuilder.buildLockedTask();
            when(taskRepository.findById("task_001")).thenReturn(Optional.of(lockedTask));
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            OperationTask result = taskService.unlockTask("task_001");

            assertFalse(result.getIsLocked());
            assertNull(result.getLockedBy());
            assertNull(result.getLockedAt());
        }

        @Test
        @DisplayName("尝试锁定 - 成功时返回true")
        void tryLockTask_Success_ReturnsTrue() {
            OperationTask task = TestDataBuilder.buildPendingTask();
            when(taskRepository.findById("task_001")).thenReturn(Optional.of(task));
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            boolean result = taskService.tryLockTask("task_001", "operator_001");

            assertTrue(result);
        }

        @Test
        @DisplayName("尝试锁定 - 失败时返回false")
        void tryLockTask_Failure_ReturnsFalse() {
            OperationTask lockedTask = TestDataBuilder.buildLockedTask();
            when(taskRepository.findById("task_001")).thenReturn(Optional.of(lockedTask));

            boolean result = taskService.tryLockTask("task_001", "operator_002");

            assertFalse(result);
        }

        @Test
        @DisplayName("检查任务是否锁定 - 已锁定返回true")
        void isTaskLocked_Locked_ReturnsTrue() {
            OperationTask lockedTask = TestDataBuilder.buildLockedTask();
            when(taskRepository.findById("task_001")).thenReturn(Optional.of(lockedTask));

            boolean result = taskService.isTaskLocked("task_001");

            assertTrue(result);
        }

        @Test
        @DisplayName("检查任务是否锁定 - 已过期返回false")
        void isTaskLocked_Expired_ReturnsFalse() {
            OperationTask expiredTask = TestDataBuilder.buildExpiredLockTask();
            when(taskRepository.findById("task_001")).thenReturn(Optional.of(expiredTask));

            boolean result = taskService.isTaskLocked("task_001");

            assertFalse(result);
        }

        @Test
        @DisplayName("已完成任务不能锁定")
        void lockTask_CompletedTask_ThrowsException() {
            OperationTask completedTask = TestDataBuilder.buildCompletedTask();
            when(taskRepository.findById("task_004")).thenReturn(Optional.of(completedTask));

            DeviceOpsException exception = assertThrows(DeviceOpsException.class, () -> {
                taskService.lockTask("task_004", "operator_001");
            });

            assertTrue(exception.getMessage().contains("任务已完成"));
        }
    }

    @Nested
    @DisplayName("锁定超时差异测试")
    class LockTimeoutTests {

        @Test
        @DisplayName("高优先级任务 - 锁定超时为30分钟(1800秒)")
        void getLockTimeout_HighPriority_Is1800Seconds() {
            int timeout = taskService.getLockTimeoutByPriority("high");
            assertEquals(1800, timeout);
        }

        @Test
        @DisplayName("中优先级任务 - 锁定超时为60分钟(3600秒)")
        void getLockTimeout_MediumPriority_Is3600Seconds() {
            int timeout = taskService.getLockTimeoutByPriority("medium");
            assertEquals(3600, timeout);
        }

        @Test
        @DisplayName("低优先级任务 - 锁定超时为120分钟(7200秒)")
        void getLockTimeout_LowPriority_Is7200Seconds() {
            int timeout = taskService.getLockTimeoutByPriority("low");
            assertEquals(7200, timeout);
        }

        @Test
        @DisplayName("不同紧急程度锁定超时差异验证 - 高优先级超时更短")
        void lockTimeoutDifference_HighVsMediumVsLow() {
            int highTimeout = taskService.getLockTimeoutByPriority("high");
            int mediumTimeout = taskService.getLockTimeoutByPriority("medium");
            int lowTimeout = taskService.getLockTimeoutByPriority("low");

            assertTrue(highTimeout < mediumTimeout);
            assertTrue(mediumTimeout < lowTimeout);
            assertEquals(1800, highTimeout);
            assertEquals(3600, mediumTimeout);
            assertEquals(7200, lowTimeout);
        }

        @Test
        @DisplayName("锁过期时间计算正确")
        void isLockExpired_CalculationCorrect() {
            OperationTask task = TestDataBuilder.buildLockedTask();
            task.setLockTimeoutSeconds(1800);
            task.setLockedAt(LocalDateTime.now());

            assertFalse(taskService.isLockExpired(task));

            task.setLockedAt(LocalDateTime.now().minusMinutes(31));
            assertTrue(taskService.isLockExpired(task));
        }
    }

    @Nested
    @DisplayName("任务执行测试")
    class TaskExecutionTests {

        @Test
        @DisplayName("执行任务成功 - 状态转为processing")
        void executeTask_Success() {
            OperationTask assignedTask = TestDataBuilder.buildAssignedTask();
            when(taskRepository.findById("task_002")).thenReturn(Optional.of(assignedTask));
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            OperationTask result = taskService.executeTask("task_002", "operator_001");

            assertEquals("processing", result.getTaskStatus());
            verify(historyService, times(1)).recordTaskExecute(
                    eq("device_001"), eq("task_002"), anyString());
        }

        @Test
        @DisplayName("执行已完成任务 - 抛出异常")
        void executeTask_CompletedTask_ThrowsException() {
            OperationTask completedTask = TestDataBuilder.buildCompletedTask();
            when(taskRepository.findById("task_004")).thenReturn(Optional.of(completedTask));

            DeviceOpsException exception = assertThrows(DeviceOpsException.class, () -> {
                taskService.executeTask("task_004", "operator_001");
            });

            assertTrue(exception.getMessage().contains("任务已完成"));
        }

        @Test
        @DisplayName("执行无人员任务 - 分配人员")
        void executeTask_NoOperator_AssignsOperator() {
            OperationTask pendingTask = TestDataBuilder.buildPendingTask();
            pendingTask.setOperatorId(null);
            when(taskRepository.findById("task_001")).thenReturn(Optional.of(pendingTask));
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            OperationTask result = taskService.executeTask("task_001", "operator_001");

            assertEquals("operator_001", result.getOperatorId());
            assertEquals("processing", result.getTaskStatus());
        }

        @Test
        @DisplayName("完成任务成功 - 状态转为completed")
        void completeTask_Success() {
            OperationTask processingTask = TestDataBuilder.buildProcessingTask();
            when(taskRepository.findById("task_003")).thenReturn(Optional.of(processingTask));
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            OperationTask result = taskService.completeTask("task_003", "设备已修复");

            assertEquals("completed", result.getTaskStatus());
            assertEquals("设备已修复", result.getResult());
            assertNotNull(result.getCompletedAt());
        }

        @Test
        @DisplayName("完成任务时 - 释放运维人员")
        void completeTask_ReleasesOperator() {
            OperationTask processingTask = TestDataBuilder.buildProcessingTask();
            when(taskRepository.findById("task_003")).thenReturn(Optional.of(processingTask));
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            taskService.completeTask("task_003", "设备已修复");

            verify(operatorService, times(1)).releaseOperator("operator_001");
            verify(operatorService, times(1)).incrementCompletedCount("operator_001");
        }

        @Test
        @DisplayName("完成任务时 - 更新故障状态")
        void completeTask_UpdatesFaultStatus() {
            OperationTask processingTask = TestDataBuilder.buildProcessingTask();
            when(taskRepository.findById("task_003")).thenReturn(Optional.of(processingTask));
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            taskService.completeTask("task_003", "设备已修复");

            verify(faultService, times(1)).resolveFault("fault_001", "operator_001");
        }

        @Test
        @DisplayName("完成任务时 - 记录历史")
        void completeTask_RecordsHistory() {
            OperationTask processingTask = TestDataBuilder.buildProcessingTask();
            when(taskRepository.findById("task_003")).thenReturn(Optional.of(processingTask));
            when(taskRepository.save(any(OperationTask.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            taskService.completeTask("task_003", "设备已修复");

            verify(historyService, times(1)).recordTaskComplete(
                    eq("device_001"), eq("task_003"), anyString(), eq("fault_001"));
        }

        @Test
        @DisplayName("完成已完成任务 - 抛出异常")
        void completeTask_AlreadyCompleted_ThrowsException() {
            OperationTask completedTask = TestDataBuilder.buildCompletedTask();
            when(taskRepository.findById("task_004")).thenReturn(Optional.of(completedTask));

            DeviceOpsException exception = assertThrows(DeviceOpsException.class, () -> {
                taskService.completeTask("task_004", "再次完成");
            });

            assertTrue(exception.getMessage().contains("任务已完成"));
        }
    }

    @Nested
    @DisplayName("任务查询测试")
    class TaskQueryTests {

        @Test
        @DisplayName("查询任务成功")
        void getTask_Success() {
            OperationTask task = TestDataBuilder.buildPendingTask();
            when(taskRepository.findById("task_001")).thenReturn(Optional.of(task));

            OperationTask result = taskService.getTask("task_001");

            assertNotNull(result);
            assertEquals("task_001", result.getTaskId());
        }

        @Test
        @DisplayName("查询任务不存在时抛出异常")
        void getTask_NotFound_ThrowsException() {
            when(taskRepository.findById("task_999")).thenReturn(Optional.empty());

            DeviceOpsException exception = assertThrows(DeviceOpsException.class, () -> {
                taskService.getTask("task_999");
            });

            assertEquals(404, exception.getCode());
        }

        @Test
        @DisplayName("查询所有任务")
        void getAllTasks_ReturnsList() {
            List<OperationTask> tasks = new ArrayList<>();
            tasks.add(TestDataBuilder.buildPendingTask());
            tasks.add(TestDataBuilder.buildProcessingTask());
            when(taskRepository.findAll()).thenReturn(tasks);

            List<OperationTask> result = taskService.getAllTasks();

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("按状态查询任务")
        void getTasksByStatus_ReturnsMatching() {
            List<OperationTask> pendingTasks = new ArrayList<>();
            pendingTasks.add(TestDataBuilder.buildPendingTask());
            when(taskRepository.findByTaskStatus("pending")).thenReturn(pendingTasks);

            List<OperationTask> result = taskService.getTasksByStatus("pending");

            assertEquals(1, result.size());
            assertEquals("pending", result.get(0).getTaskStatus());
        }

        @Test
        @DisplayName("按设备查询任务")
        void getTasksByDevice_ReturnsRecords() {
            when(deviceService.exists("device_001")).thenReturn(true);
            List<OperationTask> tasks = new ArrayList<>();
            tasks.add(TestDataBuilder.buildPendingTask());
            when(taskRepository.findByDeviceIdOrderByTaskTimeDesc("device_001"))
                    .thenReturn(tasks);

            List<OperationTask> result = taskService.getTasksByDevice("device_001");

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("统计待处理任务数量")
        void countByStatus_PendingTasks() {
            when(taskRepository.countByTaskStatus("pending")).thenReturn(10L);

            long count = taskService.countByStatus("pending");

            assertEquals(10L, count);
        }

        @Test
        @DisplayName("统计已完成任务数量")
        void countByStatus_CompletedTasks() {
            when(taskRepository.countByTaskStatus("completed")).thenReturn(50L);

            long count = taskService.countByStatus("completed");

            assertEquals(50L, count);
        }
    }
}
