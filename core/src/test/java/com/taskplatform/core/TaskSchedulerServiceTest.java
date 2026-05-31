package com.taskplatform.core;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskplatform.common.enums.TaskPriority;
import com.taskplatform.common.enums.TaskStatus;
import com.taskplatform.config.ConfigService;
import com.taskplatform.persistence.entity.Task;
import com.taskplatform.persistence.mapper.TaskMapper;
import com.taskplatform.test.builder.TaskBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("任务调度服务测试 - 调度与清理边界条件")
class TaskSchedulerServiceTest {

    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskExecutorService taskExecutorService;
    @Mock
    private ConfigService configService;

    private TaskSchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        schedulerService = new TaskSchedulerService(
                taskMapper,
                taskExecutorService,
                configService
        );
    }

    @Nested
    @DisplayName("任务调度测试")
    class TaskSchedulingTests {

        @Test
        @DisplayName("正常调度 - 应按优先级排序任务")
        void shouldScheduleTasksByPriority() {
            Task lowPriorityTask = TaskBuilder.aTask()
                    .withTaskId("low-001")
                    .withStatus(TaskStatus.QUEUED)
                    .withPriority(TaskPriority.LOW)
                    .build();
            Task normalPriorityTask = TaskBuilder.aTask()
                    .withTaskId("normal-001")
                    .withStatus(TaskStatus.QUEUED)
                    .withPriority(TaskPriority.NORMAL)
                    .build();
            Task highPriorityTask = TaskBuilder.aTask()
                    .withTaskId("high-001")
                    .withStatus(TaskStatus.QUEUED)
                    .withPriority(TaskPriority.HIGH)
                    .build();
            Task criticalTask = TaskBuilder.aTask()
                    .withTaskId("critical-001")
                    .withStatus(TaskStatus.QUEUED)
                    .withPriority(TaskPriority.CRITICAL)
                    .build();

            when(configService.getInt(eq("task"), eq("scheduler.batch.size"), anyInt())).thenReturn(10);
            when(taskMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(criticalTask, highPriorityTask, normalPriorityTask, lowPriorityTask));

            schedulerService.scheduleTasks();

            ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
            verify(taskExecutorService, timeout(1000).times(4)).executeTask(taskCaptor.capture());

            List<Task> executedTasks = taskCaptor.getAllValues();
            assertThat(executedTasks).extracting(Task::getTaskId)
                    .containsExactly("critical-001", "high-001", "normal-001", "low-001");
        }

        @Test
        @DisplayName("调度批量限制 - 不应超过批处理大小")
        void shouldRespectBatchSizeLimit() {
            List<Task> manyTasks = java.util.stream.IntStream.range(0, 15)
                    .mapToObj(i -> TaskBuilder.aTask()
                            .withTaskId("task-" + i)
                            .buildQueuedTask())
                    .toList();

            when(configService.getInt(eq("task"), eq("scheduler.batch.size"), anyInt())).thenReturn(5);
            when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(manyTasks);

            schedulerService.scheduleTasks();

            verify(taskExecutorService, timeout(1000).times(5)).executeTask(anyString());
        }

        @Test
        @DisplayName("空队列 - 不应执行任何任务")
        void shouldHandleEmptyQueue() {
            when(configService.getInt(eq("task"), eq("scheduler.batch.size"), anyInt())).thenReturn(10);
            when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            schedulerService.scheduleTasks();

            verify(taskExecutorService, never()).executeTask(anyString());
        }

        @Test
        @DisplayName("调度时间控制 - 应跳过未到时间的任务")
        void shouldSkipTasksNotReadyForScheduling() {
            Task futureTask = TaskBuilder.aTask()
                    .withTaskId("future-001")
                    .withStatus(TaskStatus.QUEUED)
                    .withScheduledAt(LocalDateTime.now().plusHours(1))
                    .build();
            Task readyTask = TaskBuilder.aTask()
                    .withTaskId("ready-001")
                    .withStatus(TaskStatus.QUEUED)
                    .withScheduledAt(LocalDateTime.now().minusMinutes(5))
                    .build();

            when(configService.getInt(eq("task"), eq("scheduler.batch.size"), anyInt())).thenReturn(10);
            when(taskMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(futureTask, readyTask));

            schedulerService.scheduleTasks();

            verify(taskExecutorService, timeout(1000).times(1)).executeTask("ready-001");
            verify(taskExecutorService, never()).executeTask("future-001");
        }

        @Test
        @DisplayName("异常处理 - 调度异常不应影响后续调度")
        void shouldContinueSchedulingOnError() {
            Task task1 = TaskBuilder.aTask().withTaskId("task-001").buildQueuedTask();
            Task task2 = TaskBuilder.aTask().withTaskId("task-002").buildQueuedTask();

            when(configService.getInt(eq("task"), eq("scheduler.batch.size"), anyInt())).thenReturn(10);
            when(taskMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(task1, task2));
            doThrow(new RuntimeException("Execution failed"))
                    .when(taskExecutorService).executeTask("task-001");
            doNothing().when(taskExecutorService).executeTask("task-002");

            assertThatCode(() -> schedulerService.scheduleTasks())
                    .doesNotThrowAnyException();

            verify(taskExecutorService, timeout(1000)).executeTask("task-002");
        }
    }

    @Nested
    @DisplayName("任务清理测试")
    class TaskCleanupTests {

        @Test
        @DisplayName("卡死任务清理 - 应标记长时间未更新的任务")
        void shouldMarkStuckTasksAsFailed() {
            LocalDateTime stuckTime = LocalDateTime.now().minusMinutes(60);
            Task stuckTask = TaskBuilder.aTask()
                    .withTaskId("stuck-001")
                    .withStatus(TaskStatus.RUNNING)
                    .withUpdatedAt(stuckTime)
                    .build();

            when(configService.getInt(eq("task"), eq("stuck.timeout.minutes"), anyInt())).thenReturn(30);
            when(taskMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(stuckTask));

            schedulerService.cleanupStuckTasks();

            ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
            verify(taskMapper, times(1)).updateById(taskCaptor.capture());

            Task updatedTask = taskCaptor.getValue();
            assertThat(updatedTask.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(updatedTask.getErrorMessage()).contains("stuck");
            assertThat(updatedTask.getUpdatedAt()).isAfter(stuckTime);
        }

        @Test
        @DisplayName("正常运行任务 - 不应被清理")
        void shouldNotMarkHealthyTasks() {
            Task healthyTask = TaskBuilder.aTask()
                    .withTaskId("healthy-001")
                    .withStatus(TaskStatus.RUNNING)
                    .withUpdatedAt(LocalDateTime.now().minusMinutes(5))
                    .build();

            when(configService.getInt(eq("task"), eq("stuck.timeout.minutes"), anyInt())).thenReturn(30);
            when(taskMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of());

            schedulerService.cleanupStuckTasks();

            verify(taskMapper, never()).updateById(healthyTask);
        }

        @Test
        @DisplayName("清理边界 - 恰好等于超时时间的任务不应被清理")
        void shouldNotTaskAtExactThreshold() {
            LocalDateTime thresholdTime = LocalDateTime.now().minusMinutes(30);
            Task borderlineTask = TaskBuilder.aTask()
                    .withTaskId("borderline-001")
                    .withStatus(TaskStatus.RUNNING)
                    .withUpdatedAt(thresholdTime)
                    .build();

            when(configService.getInt(eq("task"), eq("stuck.timeout.minutes"), anyInt())).thenReturn(30);
            when(taskMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of());

            schedulerService.cleanupStuckTasks();

            verify(taskMapper, never()).updateById(borderlineTask);
        }

        @Test
        @DisplayName("多卡死任务 - 应全部清理")
        void shouldCleanupMultipleStuckTasks() {
            Task stuck1 = TaskBuilder.aTask()
                    .withTaskId("stuck-001")
                    .withStatus(TaskStatus.RUNNING)
                    .withUpdatedAt(LocalDateTime.now().minusHours(2))
                    .build();
            Task stuck2 = TaskBuilder.aTask()
                    .withTaskId("stuck-002")
                    .withStatus(TaskStatus.RUNNING)
                    .withUpdatedAt(LocalDateTime.now().minusHours(1))
                    .build();

            when(configService.getInt(eq("task"), eq("stuck.timeout.minutes"), anyInt())).thenReturn(30);
            when(taskMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(stuck1, stuck2));

            schedulerService.cleanupStuckTasks();

            ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
            verify(taskMapper, times(2)).updateById(taskCaptor.capture());

            List<Task> updatedTasks = taskCaptor.getAllValues();
            assertThat(updatedTasks).extracting(Task::getTaskId)
                    .containsExactlyInAnyOrder("stuck-001", "stuck-002");
            assertThat(updatedTasks).extracting(Task::getStatus)
                    .containsOnly(TaskStatus.FAILED);
        }
    }
}
