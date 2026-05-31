package com.taskplatform.core;

import com.taskplatform.common.enums.TaskPriority;
import com.taskplatform.common.enums.TaskStatus;
import com.taskplatform.persistence.entity.Task;
import com.taskplatform.test.builder.TaskBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TaskContextTest {

    @Test
    @DisplayName("任务上下文初始化 - 验证基本属性正确设置")
    void shouldInitializeContextCorrectly() {
        Task task = TaskBuilder.aTask()
                .withTaskId("test-task-001")
                .withType("test")
                .withPriority(TaskPriority.HIGH)
                .withTimeoutSeconds(60)
                .build();

        TaskContext context = new TaskContext(task);

        assertThat(context.getTask()).isEqualTo(task);
        assertThat(context.getTraceId()).isNotNull();
        assertThat(context.getTimeoutMs()).isEqualTo(60000L);
        assertThat(context.isCompleted()).isFalse();
        assertThat(context.isCancelled()).isFalse();
        assertThat(context.getAttributes()).isEmpty();
    }

    @Test
    @DisplayName("任务上下文属性管理 - 验证属性存取正常")
    void shouldManageAttributes() {
        Task task = TaskBuilder.aTask().build();
        TaskContext context = new TaskContext(task);

        context.setAttribute("key1", "value1");
        context.setAttribute("key2", 123);
        context.setAttribute("key3", true);

        assertThat(context.getAttribute("key1")).isEqualTo("value1");
        assertThat(context.getAttribute("key2")).isEqualTo(123);
        assertThat(context.getAttribute("key3")).isEqualTo(true);
        assertThat(context.getAttribute("nonexistent")).isNull();
        assertThat(context.getAttributes()).hasSize(3);
    }

    @ParameterizedTest
    @CsvSource({
            "1, 1000",
            "30, 30000",
            "300, 300000",
            "0, 300000"
    })
    @DisplayName("超时时间转换 - 验证秒到毫秒的正确转换")
    void shouldConvertTimeoutToMilliseconds(int timeoutSeconds, long expectedMs) {
        Task task = TaskBuilder.aTask()
                .withTimeoutSeconds(timeoutSeconds > 0 ? timeoutSeconds : null)
                .build();

        TaskContext context = new TaskContext(task);

        assertThat(context.getTimeoutMs()).isEqualTo(expectedMs);
    }

    @Test
    @DisplayName("资源释放 - 验证AutoCloseable正确释放信号量")
    void shouldReleaseResourceOnClose() throws Exception {
        Task task = TaskBuilder.aTask().build();
        TaskContext context = new TaskContext(task);
        Semaphore semaphore = new Semaphore(1);

        context.setResourceSemaphore(semaphore);
        semaphore.acquire();
        context.setResourceAcquired(true);

        assertThat(semaphore.availablePermits()).isZero();

        context.close();

        assertThat(semaphore.availablePermits()).isEqualTo(1);
        assertThat(context.isResourceAcquired()).isFalse();
    }

    @Test
    @DisplayName("剩余时间计算 - 验证剩余时间递减")
    void shouldCalculateRemainingTime() throws InterruptedException {
        Task task = TaskBuilder.aTask()
                .withTimeoutSeconds(1)
                .build();

        TaskContext context = new TaskContext(task);

        long remainingAtStart = context.getRemainingTimeMs();
        Thread.sleep(100);
        long remainingAfterWait = context.getRemainingTimeMs();

        assertThat(remainingAtStart).isCloseTo(1000L, within(100L));
        assertThat(remainingAfterWait).isLessThan(remainingAtStart);
        assertThat(remainingAfterWait).isGreaterThan(0);
    }

    @Test
    @DisplayName("超时检测 - 任务超时后应正确标识")
    void shouldDetectTimeout() throws InterruptedException {
        Task task = TaskBuilder.aTask()
                .withTimeoutSeconds(1)
                .build();

        TaskContext context = new TaskContext(task);

        assertThat(context.isTimedOut()).isFalse();

        Thread.sleep(1100);

        assertThat(context.isTimedOut()).isTrue();
    }

    @Test
    @DisplayName("上下文关闭 - 多次关闭不抛出异常")
    void shouldNotThrowOnMultipleClose() {
        Task task = TaskBuilder.aTask().build();
        TaskContext context = new TaskContext(task);
        Semaphore semaphore = new Semaphore(1);

        context.setResourceSemaphore(semaphore);
        context.setResourceAcquired(true);

        context.close();
        context.close();

        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }
}
