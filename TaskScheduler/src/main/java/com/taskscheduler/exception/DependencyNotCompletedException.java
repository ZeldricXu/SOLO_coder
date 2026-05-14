package com.taskscheduler.exception;

public class DependencyNotCompletedException extends RuntimeException {

    public DependencyNotCompletedException(String message) {
        super(message);
    }

    public DependencyNotCompletedException(String taskId, String dependencyTaskId) {
        super("Dependency task not completed: " + dependencyTaskId + " for task: " + taskId);
    }
}
