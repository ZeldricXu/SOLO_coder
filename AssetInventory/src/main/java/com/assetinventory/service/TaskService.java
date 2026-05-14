package com.assetinventory.service;

import com.assetinventory.config.LockConfig;
import com.assetinventory.entity.InventoryPerson;
import com.assetinventory.entity.InventoryTask;
import com.assetinventory.exception.InventoryException;
import com.assetinventory.repository.InventoryTaskRepository;
import com.assetinventory.util.IdGenerator;
import com.assetinventory.util.TaskLockManager;
import com.assetinventory.util.TaskLockManager.TaskLock;
import com.assetinventory.util.TaskLockManager.TaskPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class TaskService {

    private static final Logger logger = LoggerFactory.getLogger(TaskService.class);

    private final InventoryTaskRepository taskRepository;
    private final PlanService planService;
    private final PersonService personService;
    private final StatisticsService statisticsService;
    private final HistoryService historyService;
    private final TaskLockManager taskLockManager;
    private final LockConfig lockConfig;

    @Autowired
    public TaskService(InventoryTaskRepository taskRepository,
                       PlanService planService,
                       PersonService personService,
                       StatisticsService statisticsService,
                       HistoryService historyService,
                       TaskLockManager taskLockManager,
                       LockConfig lockConfig) {
        this.taskRepository = taskRepository;
        this.planService = planService;
        this.personService = personService;
        this.statisticsService = statisticsService;
        this.historyService = historyService;
        this.taskLockManager = taskLockManager;
        this.lockConfig = lockConfig;
    }

    public InventoryTask createTask(String planId, String taskRange) {
        return createTask(planId, taskRange, null);
    }

    public InventoryTask createTask(String planId, String taskRange, String priorityName) {
        planService.validatePlanActive(planId);

        TaskPriority priority = TaskPriority.fromString(priorityName);
        String effectivePriority = priority.getConfigKey();

        InventoryTask task = new InventoryTask();
        task.setTaskId(IdGenerator.generateTaskId());
        task.setPlanId(planId);
        task.setTaskRange(taskRange);
        task.setTaskStatus("pending");
        task.setTaskPriority(effectivePriority);
        task.setCreatedAt(IdGenerator.now());

        task = taskRepository.save(task);

        TaskLock lock = null;
        try {
            lock = taskLockManager.tryAcquireLock(task.getTaskId(), "task-service", priority);
            if (lock == null) {
                throw new InventoryException(409, "任务锁定失败，请稍后重试");
            }

            logger.info("Task {} locked with priority: {}, timeout: {}ms",
                    task.getTaskId(), lock.getPriorityName(), lock.getTimeoutMs());

            InventoryPerson assignedPerson = personService.assignTaskToPerson();
            task.setAssignedPerson(assignedPerson.getPersonId());
            task.setAssignedAt(IdGenerator.now());
            task.setTaskStatus("assigned");

            task = taskRepository.save(task);

            personService.incrementTaskCount(assignedPerson.getPersonId());

            statisticsService.incrementTaskCount();

            historyService.recordTaskHistory(task.getTaskId(), "CREATE",
                    "创建盘点任务: " + task.getTaskId() +
                            ", 优先级: " + lock.getPriorityName() +
                            ", 分配给: " + assignedPerson.getPersonName());

            return task;

        } finally {
            if (lock != null) {
                taskLockManager.releaseLock(lock);
                logger.info("Task {} lock released", task.getTaskId());
            }
        }
    }

    public TaskLock acquireTaskLock(String taskId, String holder) {
        InventoryTask task = getTaskByIdOrThrow(taskId);
        TaskPriority priority = TaskPriority.fromString(task.getTaskPriority());
        return taskLockManager.tryAcquireLock(taskId, holder, priority);
    }

    public boolean releaseTaskLock(TaskLock lock) {
        return taskLockManager.releaseLock(lock);
    }

    public boolean isTaskLocked(String taskId) {
        return taskLockManager.isLocked(taskId);
    }

    public TaskLock getCurrentTaskLock(String taskId) {
        return taskLockManager.getCurrentLock(taskId);
    }

    public long getLockTimeoutForTask(String taskId) {
        InventoryTask task = getTaskByIdOrThrow(taskId);
        TaskPriority priority = TaskPriority.fromString(task.getTaskPriority());
        return taskLockManager.getTimeoutForPriority(priority);
    }

    public List<InventoryTask> getAllTasks() {
        return taskRepository.findAll();
    }

    public List<InventoryTask> getTasksByStatus(String status) {
        return taskRepository.findByTaskStatus(status);
    }

    public List<InventoryTask> getTasksByPlanId(String planId) {
        return taskRepository.findByPlanId(planId);
    }

    public List<InventoryTask> getTasksByPriority(String priority) {
        return taskRepository.findAll().stream()
                .filter(task -> priority.equalsIgnoreCase(task.getTaskPriority()))
                .toList();
    }

    public Optional<InventoryTask> getTaskById(String taskId) {
        return taskRepository.findByTaskId(taskId);
    }

    public InventoryTask getTaskByIdOrThrow(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new InventoryException(404, "盘点任务不存在: " + taskId));
    }

    public void validateTaskPendingOrAssigned(String taskId) {
        InventoryTask task = getTaskByIdOrThrow(taskId);
        if ("completed".equals(task.getTaskStatus())) {
            throw new InventoryException(400, "任务已完成，无法执行");
        }
    }

    public InventoryTask updateTaskStatus(String taskId, String status) {
        InventoryTask task = getTaskByIdOrThrow(taskId);
        task.setTaskStatus(status);
        return taskRepository.save(task);
    }

    public InventoryTask updateTaskPriority(String taskId, String priorityName) {
        InventoryTask task = getTaskByIdOrThrow(taskId);
        TaskPriority priority = TaskPriority.fromString(priorityName);
        task.setTaskPriority(priority.getConfigKey());
        return taskRepository.save(task);
    }

    public int getActiveLockCount() {
        return taskLockManager.getActiveLockCount();
    }

    public void clearAllTaskLocks() {
        taskLockManager.clearAllLocks();
    }

    public String getDefaultPriority() {
        return lockConfig.getDefaultPriority();
    }

    public List<String> getAvailablePriorities() {
        return lockConfig.getPriority().keySet().stream().toList();
    }
}
