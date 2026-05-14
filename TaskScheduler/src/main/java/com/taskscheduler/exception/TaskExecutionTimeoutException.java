package com.taskscheduler.exception;

public class TaskExecutionTimeoutException extends RuntimeException {

    public TaskExecutionTimeoutException(String message) {
        super(message);
    }

    public TaskExecutionTimeoutException(String taskId, long timeoutSeconds) {
        super("Task execution timeout: " + taskId + ". Timeout: " + timeoutSeconds + " seconds");
    }
}
