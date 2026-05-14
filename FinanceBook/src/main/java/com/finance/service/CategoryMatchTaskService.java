package com.finance.service;

import com.finance.entity.CategoryMatchTask;
import com.finance.entity.Category;
import com.finance.repository.CategoryMatchTaskRepository;
import com.finance.util.IdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryMatchTaskService {

    public static final String TASK_STATUS_PENDING = "pending";
    public static final String TASK_STATUS_PROCESSING = "processing";
    public static final String TASK_STATUS_COMPLETED = "completed";
    public static final String TASK_STATUS_FAILED = "failed";
    public static final String TASK_STATUS_RETRY = "retry";

    private final CategoryMatchTaskRepository taskRepository;
    private final CategoryService categoryService;
    private final RedisQueueService redisQueueService;
    private final ObjectMapper objectMapper;

    @Transactional
    public CategoryMatchTask createTask(String recordId, String accountId, String recordType,
                                         String requestedCategory, String queueKey) {
        CategoryMatchTask task = CategoryMatchTask.builder()
                .taskId(IdGenerator.generateId("task"))
                .recordId(recordId)
                .accountId(accountId)
                .recordType(recordType)
                .requestedCategory(requestedCategory)
                .taskStatus(TASK_STATUS_PENDING)
                .retryCount(0)
                .maxRetries(3)
                .queueKey(queueKey)
                .createdAt(LocalDateTime.now())
                .build();

        CategoryMatchTask saved = taskRepository.save(task);
        log.info("创建分类匹配任务: taskId={}, recordId={}", saved.getTaskId(), recordId);
        return saved;
    }

    @Transactional
    public String submitTaskToQueue(CategoryMatchTask task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            String messageId = redisQueueService.pushToQueue(task.getQueueKey(), taskJson);
            task.setTaskStatus(TASK_STATUS_PENDING);
            taskRepository.save(task);
            log.debug("任务已提交到Redis队列: taskId={}, queueKey={}", task.getTaskId(), task.getQueueKey());
            return messageId;
        } catch (JsonProcessingException e) {
            log.error("序列化任务失败: taskId={}", task.getTaskId(), e);
            throw new RuntimeException("序列化任务失败", e);
        }
    }

    @Transactional(readOnly = true)
    public CategoryMatchTask getTaskById(String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));
    }

    @Transactional(readOnly = true)
    public Optional<CategoryMatchTask> getTaskByRecordId(String recordId) {
        return taskRepository.findByRecordId(recordId);
    }

    @Transactional(readOnly = true)
    public List<CategoryMatchTask> getPendingTasks() {
        return taskRepository.findByTaskStatusOrderByCreatedAtAsc(TASK_STATUS_PENDING);
    }

    @Transactional(readOnly = true)
    public List<CategoryMatchTask> getRetryableTasks() {
        return taskRepository.findByTaskStatusAndRetryCountLessThan(TASK_STATUS_RETRY, 3);
    }

    @Transactional
    public boolean processTask(CategoryMatchTask task) {
        task.setTaskStatus(TASK_STATUS_PROCESSING);
        taskRepository.save(task);

        try {
            Category matchedCategory = categoryService.matchCategory(task.getRecordType(), task.getRequestedCategory());

            if (matchedCategory != null) {
                task.setMatchedCategory(matchedCategory.getCategoryName());
                task.setTaskStatus(TASK_STATUS_COMPLETED);
                task.setProcessedAt(LocalDateTime.now());
                log.info("分类匹配成功: taskId={}, original={}, matched={}",
                        task.getTaskId(), task.getRequestedCategory(), task.getMatchedCategory());
            } else {
                task.setTaskStatus(TASK_STATUS_FAILED);
                task.setErrorMessage("无法匹配到有效分类: " + task.getRequestedCategory());
                log.warn("分类匹配失败: taskId={}, category={}", task.getTaskId(), task.getRequestedCategory());
            }

            taskRepository.save(task);
            return matchedCategory != null;

        } catch (Exception e) {
            log.error("处理分类匹配任务异常: taskId={}", task.getTaskId(), e);

            if (task.getRetryCount() < task.getMaxRetries()) {
                task.setTaskStatus(TASK_STATUS_RETRY);
                task.setRetryCount(task.getRetryCount() + 1);
                task.setScheduledAt(LocalDateTime.now().plusMinutes(5L * task.getRetryCount()));
                task.setErrorMessage(e.getMessage());
                taskRepository.save(task);
                log.info("任务已标记为重试: taskId={}, retryCount={}", task.getTaskId(), task.getRetryCount());
            } else {
                task.setTaskStatus(TASK_STATUS_FAILED);
                task.setErrorMessage("重试次数超限: " + e.getMessage());
                taskRepository.save(task);
                log.error("任务重试失败，已放弃: taskId={}", task.getTaskId());
            }

            return false;
        }
    }

    @Transactional
    public void recoverPendingTasks() {
        List<CategoryMatchTask> pendingTasks = getPendingTasks();
        log.info("恢复待处理任务: count={}", pendingTasks.size());

        for (CategoryMatchTask task : pendingTasks) {
            try {
                submitTaskToQueue(task);
            } catch (Exception e) {
                log.error("恢复任务失败: taskId={}", task.getTaskId(), e);
            }
        }

        List<CategoryMatchTask> retryTasks = getRetryableTasks();
        log.info("恢复可重试任务: count={}", retryTasks.size());

        for (CategoryMatchTask task : retryTasks) {
            if (task.getScheduledAt() != null && task.getScheduledAt().isBefore(LocalDateTime.now())) {
                try {
                    task.setTaskStatus(TASK_STATUS_PENDING);
                    submitTaskToQueue(task);
                } catch (Exception e) {
                    log.error("恢复重试任务失败: taskId={}", task.getTaskId(), e);
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public long countPendingTasks() {
        return taskRepository.countByTaskStatus(TASK_STATUS_PENDING);
    }

    @Transactional(readOnly = true)
    public long countFailedTasks() {
        return taskRepository.countByTaskStatus(TASK_STATUS_FAILED);
    }

    @Transactional(readOnly = true)
    public long countCompletedTasks() {
        return taskRepository.countByTaskStatus(TASK_STATUS_COMPLETED);
    }
}
