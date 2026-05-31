package com.taskplatform.core.handler;

import com.taskplatform.common.util.JsonUtil;
import com.taskplatform.core.TaskContext;
import com.taskplatform.core.TaskHandler;
import com.taskplatform.persistence.entity.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component
public class DefaultTaskHandler implements TaskHandler {

    private static final String TASK_TYPE = "default";
    private static final int SIMULATED_PROCESSING_MS = 100;

    @Override
    public String getTaskType() {
        return TASK_TYPE;
    }

    @Override
    public boolean canHandle(String taskType) {
        return TASK_TYPE.equals(taskType) || "generic".equals(taskType);
    }

    @Override
    public Object execute(TaskContext context) throws Exception {
        Task task = context.getTask();
        log.debug("Executing default task: {} - {}", task.getTaskId(), task.getName());

        Map<String, Object> payload = parsePayload(task);

        Map<String, Object> result = Map.of(
                "taskId", task.getTaskId(),
                "processedAt", LocalDateTime.now().toString(),
                "inputSize", payload.size(),
                "status", "completed",
                "message", "Task executed successfully with default handler"
        );

        Thread.sleep(SIMULATED_PROCESSING_MS);

        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(Task task) {
        if (task.getPayload() == null) {
            return Map.of();
        }
        try {
            return JsonUtil.fromJson(task.getPayload(), Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse task payload: {}", task.getTaskId(), e);
            return Map.of();
        }
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }
}
