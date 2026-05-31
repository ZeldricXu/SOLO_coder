package com.parking.platform.core.service;

import com.parking.platform.common.constant.Constants;
import com.parking.platform.common.exception.ResourceNotFoundException;
import com.parking.platform.common.exception.ValidationException;
import com.parking.platform.core.entity.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final Map<String, Task> taskStore = new ConcurrentHashMap<>();
    private final TaskExecutor taskExecutor;
    private final EventEmitter eventEmitter;

    public TaskService(TaskExecutor taskExecutor, EventEmitter eventEmitter) {
        this.taskExecutor = taskExecutor;
        this.eventEmitter = eventEmitter;
    }

    public Task createTask(Task task) {
        if (task.getType() == null || task.getType().isEmpty()) {
            throw new ValidationException("Task type is required");
        }

        task.setStatus(Constants.STATUS_PENDING);
        task.setPhase(Constants.PHASE_QUEUED);
        task.setProgress(0.0);
        taskStore.put(task.getId(), task);

        log.info("Task created: {}", task.getId());
        eventEmitter.emit(Constants.EVENT_TASK_CREATED, task);
        return task;
    }

    public Task getTask(String id) {
        Task task = taskStore.get(id);
        if (task == null) {
            throw new ResourceNotFoundException("Task", id);
        }
        return task;
    }

    public Task updateTaskStatus(String id, String status, String phase, Double progress) {
        Task task = getTask(id);
        if (status != null) task.setStatus(status);
        if (phase != null) task.setPhase(phase);
        if (progress != null) task.setProgress(progress);
        task.touch();
        return task;
    }

    public Task submitTask(Task task) {
        Task created = createTask(task);
        taskExecutor.executeTask(created);
        eventEmitter.emit(Constants.EVENT_TASK_STARTED, created);
        return created;
    }

    public Task cancelTask(String id) {
        Task task = getTask(id);
        if (task.isCompleted()) {
            throw new ValidationException("Cannot cancel completed task");
        }
        task.setStatus(Constants.STATUS_CANCELLED);
        task.setCompletedAt(Instant.now());
        task.touch();
        log.info("Task cancelled: {}", id);
        return task;
    }

    public List<Task> listTasks(String status, String type, Integer page, Integer size) {
        List<Task> tasks = new ArrayList<>(taskStore.values());

        if (status != null) {
            tasks.removeIf(t -> !status.equals(t.getStatus()));
        }
        if (type != null) {
            tasks.removeIf(t -> !type.equals(t.getType()));
        }

        tasks.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        int pageNum = page != null ? page : Constants.DEFAULT_PAGE;
        int sizeNum = size != null ? size : Constants.DEFAULT_SIZE;
        int start = (pageNum - 1) * sizeNum;
        int end = Math.min(start + sizeNum, tasks.size());

        if (start >= tasks.size()) {
            return new ArrayList<>();
        }

        return tasks.subList(start, end);
    }

    public long countTasks(String status, String type) {
        return taskStore.values().stream()
                .filter(t -> status == null || status.equals(t.getStatus()))
                .filter(t -> type == null || type.equals(t.getType()))
                .count();
    }

    public Map<String, Long> getTaskStatistics() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", (long) taskStore.size());
        stats.put("pending", countTasks(Constants.STATUS_PENDING, null));
        stats.put("running", countTasks(Constants.STATUS_RUNNING, null));
        stats.put("completed", countTasks(Constants.STATUS_COMPLETED, null));
        stats.put("failed", countTasks(Constants.STATUS_FAILED, null));
        stats.put("cancelled", countTasks(Constants.STATUS_CANCELLED, null));
        return stats;
    }

    public boolean deleteTask(String id) {
        Task task = taskStore.remove(id);
        if (task != null) {
            log.info("Task deleted: {}", id);
            return true;
        }
        return false;
    }
}
