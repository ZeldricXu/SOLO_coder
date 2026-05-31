package com.taskplatform.core;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskplatform.common.enums.TaskStatus;
import com.taskplatform.common.event.EventPublisher;
import com.taskplatform.common.exception.BusinessException;
import com.taskplatform.common.exception.TimeoutException;
import com.taskplatform.config.ConfigService;
import com.taskplatform.core.handler.DefaultTaskHandler;
import com.taskplatform.persistence.entity.Task;
import com.taskplatform.persistence.entity.TaskRun;
import com.taskplatform.persistence.mapper.TaskMapper;
import com.taskplatform.persistence.mapper.TaskRunMapper;
import com.taskplatform.test.builder.TaskBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("任务执行服务测试 - 核心边界条件")
class TaskExecutorServiceTest {

    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskRunMapper taskRunMapper;
    @Mock
    private ConfigService configService;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private DefaultTaskHandler defaultTaskHandler;

    private TaskExecutorService taskExecutorService;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        taskExecutorService = new TaskExecutorService(
                taskMapper,
                taskRunMapper,
                configService,
                eventPublisher,
                List.of(defaultTaskHandler),
                meterRegistry
        );
    }

    @Nested
    @DisplayName("任务提交测试")
    class TaskSubmissionTests {

        @Test
        @DisplayName("正常任务提交 - 应正确设置初始状态")
        void shouldSubmitTaskSuccessfully() {
            Task task = TaskBuilder.aTask()
                    .withName("Test Task")
                    .withType("default")
                    .build();

            when(taskMapper.insert(any(Task.class))).thenReturn(1);

            Task result = taskExecutorService.submitTask(task);

            assertThat(result.getTaskId()).isNotNull();
            assertThat(result.getStatus()).isEqualTo(TaskStatus.QUEUED);
            assertThat(result.getCreatedAt()).isNotNull();
            assertThat(result.getUpdatedAt()).isNotNull();
            verify(taskMapper, times(1)).insert(any(Task.class));
            verify(eventPublisher, times(1)).publish(any());
        }

        @Test
        @DisplayName("任务提交 - 空任务名称不应抛出异常")
        void shouldHandleTaskWithEmptyName() {
            Task task = TaskBuilder.aTask()
                    .withName("")
                    .withType("default")
                    .build();

            when(taskMapper.insert(any(Task.class))).thenReturn(1);

            assertThatCode(() -> taskExecutorService.submitTask(task))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("任务提交 - 超大任务名称应正确处理")
        void shouldHandleVeryLongTaskName() {
            String longName = "a".repeat(10000);
            Task task = TaskBuilder.aTask()
                    .withName(longName)
                    .withType("default")
                    .build();

            when(taskMapper.insert(any(Task.class))).thenReturn(1);

            Task result = taskExecutorService.submitTask(task);

            assertThat(result.getName()).isEqualTo(longName);
        }
    }

    @Nested
    @DisplayName("任务执行测试")
    class TaskExecutionTests {

        @Test
        @DisplayName("正常任务执行 - 应返回执行结果")
        void shouldExecuteTaskSuccessfully() throws Exception {
            Task task = TaskBuilder.aTask()
                    .withTaskId("exec-test-001")
                    .withType("default")
                    .withPayload("{\"data\": \"test\"}")
                    .build();

            when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
            when(defaultTaskHandler.canHandle("default")).thenReturn(true);
            when(defaultTaskHandler.execute(any(TaskContext.class))).thenReturn("SUCCESS");
            when(taskMapper.updateById(any(Task.class))).thenReturn(1);
            when(taskRunMapper.insert(any(TaskRun.class))).thenReturn(1);
            when(taskRunMapper.updateById(any(TaskRun.class))).thenReturn(1);
            when(configService.getInt(eq("task"), eq("pool.size"), anyInt())).thenReturn(100);

            Object result = taskExecutorService.executeTask("exec-test-001");

            assertThat(result).isEqualTo("SUCCESS");
            verify(taskMapper, atLeastOnce()).updateById(argThat(t ->
                    t.getStatus() == TaskStatus.RUNNING || t.getStatus() == TaskStatus.COMPLETED
            ));
        }

        @Test
        @DisplayName("任务不存在 - 应抛出404异常")
        void shouldThrowExceptionWhenTaskNotFound() {
            when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            BusinessException exception = catchThrowableOfType(
                    () -> taskExecutorService.executeTask("nonexistent-id"),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(404);
            assertThat(exception.getErrorCode()).isEqualTo("TASK_NOT_FOUND");
        }

        @Test
        @DisplayName("无处理器匹配 - 应抛出异常")
        void shouldThrowExceptionWhenNoHandlerFound() throws Exception {
            Task task = TaskBuilder.aTask()
                    .withTaskId("no-handler-001")
                    .withType("unknown-type")
                    .build();

            when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
            when(defaultTaskHandler.canHandle("unknown-type")).thenReturn(false);
            when(taskRunMapper.insert(any(TaskRun.class))).thenReturn(1);
            when(configService.getInt(eq("task"), eq("pool.size"), anyInt())).thenReturn(100);

            BusinessException exception = catchThrowableOfType(
                    () -> taskExecutorService.executeTask("no-handler-001"),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getErrorCode()).isEqualTo("NO_HANDLER");
        }

        @Test
        @DisplayName("处理器抛出业务异常 - 应正确传播")
        void shouldPropagateBusinessException() throws Exception {
            Task task = TaskBuilder.aTask()
                    .withTaskId("business-error-001")
                    .withType("default")
                    .build();

            when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
            when(defaultTaskHandler.canHandle("default")).thenReturn(true);
            when(defaultTaskHandler.execute(any(TaskContext.class)))
                    .thenThrow(new BusinessException(400, "VALIDATION_FAILED", "Invalid data"));
            when(taskRunMapper.insert(any(TaskRun.class))).thenReturn(1);
            when(taskMapper.updateById(any(Task.class))).thenReturn(1);
            when(taskRunMapper.updateById(any(TaskRun.class))).thenReturn(1);
            when(configService.getInt(eq("task"), eq("pool.size"), anyInt())).thenReturn(100);

            BusinessException exception = catchThrowableOfType(
                    () -> taskExecutorService.executeTask("business-error-001"),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getErrorCode()).isEqualTo("VALIDATION_FAILED");
        }

        @Test
        @DisplayName("运行时异常 - 应包装为业务异常")
        void shouldWrapRuntimeException() throws Exception {
            Task task = TaskBuilder.aTask()
                    .withTaskId("runtime-error-001")
                    .withType("default")
                    .build();

            when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
            when(defaultTaskHandler.canHandle("default")).thenReturn(true);
            when(defaultTaskHandler.execute(any(TaskContext.class)))
                    .thenThrow(new NullPointerException("Unexpected null"));
            when(taskRunMapper.insert(any(TaskRun.class))).thenReturn(1);
            when(taskMapper.updateById(any(Task.class))).thenReturn(1);
            when(taskRunMapper.updateById(any(TaskRun.class))).thenReturn(1);
            when(configService.getInt(eq("task"), eq("pool.size"), anyInt())).thenReturn(100);

            BusinessException exception = catchThrowableOfType(
                    () -> taskExecutorService.executeTask("runtime-error-001"),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(500);
            assertThat(exception.getMessage()).contains("Unexpected null");
        }
    }

    @Nested
    @DisplayName("重试机制测试")
    class RetryMechanismTests {

        @Test
        @DisplayName("首次失败 - 应重试而不是标记失败")
        void shouldRetryOnFirstFailure() throws Exception {
            Task task = TaskBuilder.aTask()
                    .withTaskId("retry-test-001")
                    .withType("default")
                    .withMaxRetries(3)
                    .withRetryCount(0)
                    .build();

            when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
            when(defaultTaskHandler.canHandle("default")).thenReturn(true);
            when(defaultTaskHandler.execute(any(TaskContext.class)))
                    .thenThrow(new RuntimeException("Transient error"));
            when(taskRunMapper.insert(any(TaskRun.class))).thenReturn(1);
            when(taskMapper.updateById(any(Task.class))).thenReturn(1);
            when(taskRunMapper.updateById(any(TaskRun.class))).thenReturn(1);
            when(configService.getInt(eq("task"), eq("pool.size"), anyInt())).thenReturn(100);

            try {
                taskExecutorService.executeTask("retry-test-001");
            } catch (Exception ignored) {}

            ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
            verify(taskMapper, atLeastOnce()).updateById(taskCaptor.capture());

            boolean hasPendingStatus = taskCaptor.getAllValues().stream()
                    .anyMatch(t -> t.getStatus() == TaskStatus.PENDING);
            assertThat(hasPendingStatus).isTrue();
        }

        @Test
        @DisplayName("重试耗尽 - 应标记为失败")
        void shouldMarkAsFailedWhenRetriesExhausted() throws Exception {
            Task task = TaskBuilder.aTask()
                    .withTaskId("retry-exhausted-001")
                    .withType("default")
                    .withMaxRetries(3)
                    .withRetryCount(3)
                    .build();

            when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
            when(defaultTaskHandler.canHandle("default")).thenReturn(true);
            when(defaultTaskHandler.execute(any(TaskContext.class)))
                    .thenThrow(new RuntimeException("Persistent error"));
            when(taskRunMapper.insert(any(TaskRun.class))).thenReturn(1);
            when(taskMapper.updateById(any(Task.class))).thenReturn(1);
            when(taskRunMapper.updateById(any(TaskRun.class))).thenReturn(1);
            when(configService.getInt(eq("task"), eq("pool.size"), anyInt())).thenReturn(100);

            try {
                taskExecutorService.executeTask("retry-exhausted-001");
            } catch (Exception ignored) {}

            ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
            verify(taskMapper, atLeastOnce()).updateById(taskCaptor.capture());

            boolean hasFailedStatus = taskCaptor.getAllValues().stream()
                    .anyMatch(t -> t.getStatus() == TaskStatus.FAILED);
            assertThat(hasFailedStatus).isTrue();
        }

        @Test
        @DisplayName("零重试次数 - 首次失败立即标记失败")
        void shouldFailImmediatelyWithZeroRetries() throws Exception {
            Task task = TaskBuilder.aTask()
                    .withTaskId("zero-retry-001")
                    .withType("default")
                    .withMaxRetries(0)
                    .withRetryCount(0)
                    .build();

            when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
            when(defaultTaskHandler.canHandle("default")).thenReturn(true);
            when(defaultTaskHandler.execute(any(TaskContext.class)))
                    .thenThrow(new RuntimeException("Error"));
            when(taskRunMapper.insert(any(TaskRun.class))).thenReturn(1);
            when(taskMapper.updateById(any(Task.class))).thenReturn(1);
            when(taskRunMapper.updateById(any(TaskRun.class))).thenReturn(1);
            when(configService.getInt(eq("task"), eq("pool.size"), anyInt())).thenReturn(100);

            try {
                taskExecutorService.executeTask("zero-retry-001");
            } catch (Exception ignored) {}

            ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
            verify(taskMapper, atLeastOnce()).updateById(taskCaptor.capture());

            boolean hasFailedStatus = taskCaptor.getAllValues().stream()
                    .anyMatch(t -> t.getStatus() == TaskStatus.FAILED);
            assertThat(hasFailedStatus).isTrue();
        }
    }

    @Nested
    @DisplayName("任务状态转换测试")
    class StateTransitionTests {

        @Test
        @DisplayName("正常状态流转 - QUEUED -> RUNNING -> COMPLETED")
        void shouldTransitionThroughCorrectStates() throws Exception {
            Task task = TaskBuilder.aTask()
                    .withTaskId("state-test-001")
                    .withType("default")
                    .withStatus(TaskStatus.QUEUED)
                    .build();

            when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
            when(defaultTaskHandler.canHandle("default")).thenReturn(true);
            when(defaultTaskHandler.execute(any(TaskContext.class))).thenReturn("OK");
            when(taskMapper.updateById(any(Task.class))).thenReturn(1);
            when(taskRunMapper.insert(any(TaskRun.class))).thenReturn(1);
            when(taskRunMapper.updateById(any(TaskRun.class))).thenReturn(1);
            when(configService.getInt(eq("task"), eq("pool.size"), anyInt())).thenReturn(100);

            taskExecutorService.executeTask("state-test-001");

            ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
            verify(taskMapper, atLeast(2)).updateById(taskCaptor.capture());

            List<Task> capturedTasks = taskCaptor.getAllValues();
            assertThat(capturedTasks).extracting(Task::getStatus)
                    .containsSequence(TaskStatus.RUNNING, TaskStatus.COMPLETED);
        }

        @Test
        @DisplayName("任务完成 - 应设置完成时间")
        void shouldSetCompletionTime() throws Exception {
            Task task = TaskBuilder.aTask()
                    .withTaskId("complete-time-001")
                    .withType("default")
                    .build();

            when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
            when(defaultTaskHandler.canHandle("default")).thenReturn(true);
            when(defaultTaskHandler.execute(any(TaskContext.class))).thenReturn("OK");
            when(taskMapper.updateById(any(Task.class))).thenReturn(1);
            when(taskRunMapper.insert(any(TaskRun.class))).thenReturn(1);
            when(taskRunMapper.updateById(any(TaskRun.class))).thenReturn(1);
            when(configService.getInt(eq("task"), eq("pool.size"), anyInt())).thenReturn(100);

            taskExecutorService.executeTask("complete-time-001");

            ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
            verify(taskMapper, atLeastOnce()).updateById(taskCaptor.capture());

            Task completedTask = taskCaptor.getAllValues().stream()
                    .filter(t -> t.getStatus() == TaskStatus.COMPLETED)
                    .findFirst()
                    .orElseThrow();

            assertThat(completedTask.getCompletedAt()).isNotNull();
            assertThat(completedTask.getCompletedAt()).isAfterOrEqualTo(task.getCreatedAt());
        }
    }
}
