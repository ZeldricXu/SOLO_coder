package com.solocoder.platform.scheduler.service;

import com.solocoder.platform.scheduler.model.TaskDefinition;
import com.solocoder.platform.scheduler.model.TaskExecution;

import java.util.List;
import java.util.Optional;

public interface TaskSchedulingService {

    TaskDefinition createTask(TaskDefinition task);

    Optional<TaskDefinition> getTask(String taskId);

    List<TaskDefinition> listTasks();

    void deleteTask(String taskId);

    TaskExecution executeTask(String taskId);

    TaskExecution getExecution(String executionId);

    List<TaskExecution> getTaskExecutions(String taskId);

    List<TaskExecution> getRunningExecutions();

    boolean cancelExecution(String executionId);
}
