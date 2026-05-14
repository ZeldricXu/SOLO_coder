package com.taskscheduler.exception;

public class NoAvailableExecutorException extends RuntimeException {

    public NoAvailableExecutorException(String message) {
        super(message);
    }

    public NoAvailableExecutorException(String taskId, String taskType) {
        super("No available executor for task: " + taskId + " with type: " + taskType);
    }
}
