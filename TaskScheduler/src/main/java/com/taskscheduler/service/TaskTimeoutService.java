package com.taskscheduler.service;

import com.taskscheduler.config.TaskTimeoutConfig;
import com.taskscheduler.entity.TaskConfig;
import com.taskscheduler.repository.TaskConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskTimeoutService {

    private final TaskTimeoutConfig timeoutConfig;
    private final TaskConfigRepository taskConfigRepository;

    public int getTimeoutForTask(String taskId) {
        Optional<TaskConfig> taskOpt = taskConfigRepository.findByTaskId(taskId);
        if (taskOpt.isPresent()) {
            return getTimeoutForTaskType(taskOpt.get().getTaskType());
        }
        return timeoutConfig.getDefaultTimeout();
    }

    public int getTimeoutForTaskType(String taskType) {
        int timeout = timeoutConfig.getTimeoutForTaskType(taskType);
        log.debug("Task type {} timeout configured to {} seconds", taskType, timeout);
        return timeout;
    }

    public int applyTimeoutToTask(TaskConfig task) {
        int configuredTimeout = getTimeoutForTaskType(task.getTaskType());
        
        if (task.getTimeoutSeconds() == null || task.getTimeoutSeconds() <= 0) {
            task.setTimeoutSeconds(configuredTimeout);
            log.info("Applied default timeout {}s to task: {}", configuredTimeout, task.getTaskId());
        } else {
            log.debug("Task {} already has timeout: {}s", task.getTaskId(), task.getTimeoutSeconds());
        }
        
        return task.getTimeoutSeconds();
    }

    public boolean isLightweightTask(String taskType) {
        if (taskType == null) {
            return false;
        }
        int timeout = getTimeoutForTaskType(taskType);
        return timeout <= 60;
    }

    public boolean isHeavyTask(String taskType) {
        if (taskType == null) {
            return false;
        }
        int timeout = getTimeoutForTaskType(taskType);
        return timeout > 300;
    }

    public String getTimeoutDescription(String taskType) {
        if (timeoutConfig.hasCustomTimeout(taskType)) {
            TaskTimeoutConfig.TaskTypeTimeout config = timeoutConfig.getTaskTypes().get(taskType);
            if (config != null && config.getDescription() != null) {
                return config.getDescription();
            }
        }
        return "Default timeout configuration";
    }

    public int getDefaultTimeout() {
        return timeoutConfig.getDefaultTimeout();
    }

    public void setDefaultTimeout(int seconds) {
        timeoutConfig.setDefaultTimeout(Math.max(1, seconds));
        log.info("Default timeout updated to {} seconds", seconds);
    }

    public void configureTaskTypeTimeout(String taskType, int timeoutSeconds, String description) {
        TaskTimeoutConfig.TaskTypeTimeout timeout = new TaskTimeoutConfig.TaskTypeTimeout();
        timeout.setTimeoutSeconds(Math.max(1, timeoutSeconds));
        timeout.setDescription(description);
        timeoutConfig.getTaskTypes().put(taskType, timeout);
        log.info("Configured timeout for task type {}: {}s - {}", taskType, timeoutSeconds, description);
    }

    public void removeTaskTypeTimeout(String taskType) {
        timeoutConfig.getTaskTypes().remove(taskType);
        log.info("Removed custom timeout configuration for task type: {}", taskType);
    }
}
