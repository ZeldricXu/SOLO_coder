package com.taskplatform.core;

public interface TaskHandler {

    String getTaskType();

    boolean canHandle(String taskType);

    Object execute(TaskContext context) throws Exception;

    default int getOrder() {
        return 0;
    }
}
