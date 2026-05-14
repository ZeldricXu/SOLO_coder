package com.taskscheduler.exception;

public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String message) {
        super(message);
    }

    public TaskNotFoundException(String taskId, String message) {
        super("Task not found: " + taskId + ". " + message);
    }
}
