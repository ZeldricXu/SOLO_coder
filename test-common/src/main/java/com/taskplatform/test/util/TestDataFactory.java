package com.taskplatform.test.util;

import com.taskplatform.common.enums.TaskPriority;
import com.taskplatform.common.enums.TaskStatus;
import com.taskplatform.persistence.entity.Task;
import com.taskplatform.test.builder.TaskBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TestDataFactory {

    public static List<Task> createTaskBatch(int count, TaskStatus status) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Task task = TaskBuilder.aTask()
                    .withName("Batch Task " + i)
                    .withStatus(status)
                    .withPriority(i % 2 == 0 ? TaskPriority.HIGH : TaskPriority.NORMAL)
                    .build();
            tasks.add(task);
        }
        return tasks;
    }

    public static Task createStuckTask() {
        return TaskBuilder.aTask()
                .withName("Stuck Task")
                .withStatus(TaskStatus.RUNNING)
                .withCreatedAt(LocalDateTime.now().minusHours(2))
                .withUpdatedAt(LocalDateTime.now().minusHours(1))
                .build();
    }

    public static Task createTaskWithRetryState(int currentRetry, int maxRetries) {
        return TaskBuilder.aTask()
                .withName("Retry Task")
                .withStatus(TaskStatus.FAILED)
                .withRetryCount(currentRetry)
                .withMaxRetries(maxRetries)
                .build();
    }

    public static String generateLongString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append('x');
        }
        return sb.toString();
    }

    private TestDataFactory() {}
}
