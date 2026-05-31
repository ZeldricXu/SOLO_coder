package com.taskplatform.test.builder;

import com.taskplatform.common.enums.TaskPriority;
import com.taskplatform.common.enums.TaskStatus;
import com.taskplatform.common.util.IdGenerator;
import com.taskplatform.persistence.entity.Task;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class TaskBuilder {

    private String taskId;
    private String type = "default";
    private TaskStatus status = TaskStatus.PENDING;
    private TaskPriority priority = TaskPriority.NORMAL;
    private String name;
    private String description;
    private String payload;
    private String namespace = "default";
    private String queueName;
    private Integer maxRetries = 3;
    private Integer retryCount = 0;
    private Integer timeoutSeconds = 300;
    private LocalDateTime scheduledAt;
    private String createdBy = "test-user";
    private String labels;
    private Map<String, Object> attributes = new HashMap<>();

    public static TaskBuilder aTask() {
        return new TaskBuilder();
    }

    public TaskBuilder withTaskId(String taskId) {
        this.taskId = taskId;
        return this;
    }

    public TaskBuilder withType(String type) {
        this.type = type;
        return this;
    }

    public TaskBuilder withStatus(TaskStatus status) {
        this.status = status;
        return this;
    }

    public TaskBuilder withPriority(TaskPriority priority) {
        this.priority = priority;
        return this;
    }

    public TaskBuilder withName(String name) {
        this.name = name;
        return this;
    }

    public TaskBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public TaskBuilder withPayload(String payload) {
        this.payload = payload;
        return this;
    }

    public TaskBuilder withNamespace(String namespace) {
        this.namespace = namespace;
        return this;
    }

    public TaskBuilder withQueueName(String queueName) {
        this.queueName = queueName;
        return this;
    }

    public TaskBuilder withMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    public TaskBuilder withRetryCount(int retryCount) {
        this.retryCount = retryCount;
        return this;
    }

    public TaskBuilder withTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        return this;
    }

    public TaskBuilder withScheduledAt(LocalDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
        return this;
    }

    public TaskBuilder withCreatedBy(String createdBy) {
        this.createdBy = createdBy;
        return this;
    }

    public TaskBuilder withLabels(String labels) {
        this.labels = labels;
        return this;
    }

    public TaskBuilder withAttribute(String key, Object value) {
        this.attributes.put(key, value);
        return this;
    }

    public Task build() {
        Task task = new Task();
        task.setTaskId(taskId != null ? taskId : IdGenerator.generateTaskId());
        task.setType(type);
        task.setStatus(status);
        task.setPriority(priority);
        task.setName(name != null ? name : "Test Task - " + System.currentTimeMillis());
        task.setDescription(description);
        task.setPayload(payload);
        task.setNamespace(namespace);
        task.setQueueName(queueName);
        task.setMaxRetries(maxRetries);
        task.setRetryCount(retryCount);
        task.setTimeoutSeconds(timeoutSeconds);
        task.setScheduledAt(scheduledAt);
        task.setCreatedBy(createdBy);
        task.setLabels(labels);
        task.setAttributes(attributes);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }

    public Task buildQueuedTask() {
        this.status = TaskStatus.QUEUED;
        return build();
    }

    public Task buildRunningTask() {
        this.status = TaskStatus.RUNNING;
        return build();
    }

    public Task buildFailedTask() {
        this.status = TaskStatus.FAILED;
        return build();
    }

    public Task buildCompletedTask() {
        this.status = TaskStatus.COMPLETED;
        return build();
    }

    public Task buildCriticalPriorityTask() {
        this.priority = TaskPriority.CRITICAL;
        return build();
    }
}
