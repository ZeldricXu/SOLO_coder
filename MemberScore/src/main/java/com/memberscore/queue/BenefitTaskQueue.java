package com.memberscore.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.memberscore.dto.BenefitTaskMessage;
import com.memberscore.entity.BenefitTask;
import com.memberscore.enums.BenefitTaskStatus;
import com.memberscore.repository.BenefitTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class BenefitTaskQueue {
    
    private static final String QUEUE_KEY = "benefit:task:queue";
    private static final String PROCESSING_KEY = "benefit:task:processing";
    private static final String DEAD_LETTER_KEY = "benefit:task:dlq";
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final BenefitTaskRepository benefitTaskRepository;
    private final ChannelTopic benefitTaskTopic;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    
    public String submitTask(String memberId, String levelId, String source) {
        BenefitTaskMessage message = BenefitTaskMessage.create(memberId, levelId, source);
        
        BenefitTask task = BenefitTask.builder()
                .taskId(message.getTaskId())
                .memberId(memberId)
                .levelId(levelId)
                .status(BenefitTaskStatus.PENDING)
                .retryCount(0)
                .maxRetries(3)
                .taskData(serializeMessage(message))
                .build();
        
        benefitTaskRepository.save(task);
        
        redisTemplate.opsForList().rightPush(QUEUE_KEY, message);
        
        redisTemplate.convertAndSend(benefitTaskTopic.getTopic(), message);
        
        log.info("权益任务已提交到队列: taskId={}, memberId={}, levelId={}", 
                message.getTaskId(), memberId, levelId);
        
        return message.getTaskId();
    }
    
    public BenefitTaskMessage pollTask() {
        Object rawMessage = redisTemplate.opsForList().leftPop(QUEUE_KEY, 5, TimeUnit.SECONDS);
        if (rawMessage == null) {
            return null;
        }
        
        try {
            if (rawMessage instanceof BenefitTaskMessage) {
                return (BenefitTaskMessage) rawMessage;
            } else if (rawMessage instanceof String) {
                return objectMapper.readValue((String) rawMessage, BenefitTaskMessage.class);
            } else {
                return objectMapper.convertValue(rawMessage, BenefitTaskMessage.class);
            }
        } catch (Exception e) {
            log.error("解析任务消息失败", e);
            return null;
        }
    }
    
    public void markProcessing(BenefitTaskMessage message) {
        redisTemplate.opsForHash().put(PROCESSING_KEY, message.getTaskId(), message);
        
        benefitTaskRepository.findByTaskId(message.getTaskId()).ifPresent(task -> {
            task.setStatus(BenefitTaskStatus.PROCESSING);
            task.setProcessedAt(java.time.LocalDateTime.now());
            benefitTaskRepository.save(task);
        });
        
        log.info("任务开始处理: taskId={}", message.getTaskId());
    }
    
    public void markCompleted(BenefitTaskMessage message) {
        redisTemplate.opsForHash().delete(PROCESSING_KEY, message.getTaskId());
        
        benefitTaskRepository.findByTaskId(message.getTaskId()).ifPresent(task -> {
            task.setStatus(BenefitTaskStatus.COMPLETED);
            task.setCompletedAt(java.time.LocalDateTime.now());
            benefitTaskRepository.save(task);
        });
        
        log.info("任务处理完成: taskId={}", message.getTaskId());
    }
    
    public void markFailed(BenefitTaskMessage message, String errorMessage) {
        redisTemplate.opsForHash().delete(PROCESSING_KEY, message.getTaskId());
        
        benefitTaskRepository.findByTaskId(message.getTaskId()).ifPresent(task -> {
            int newRetryCount = task.getRetryCount() + 1;
            task.setRetryCount(newRetryCount);
            task.setErrorMessage(errorMessage);
            
            if (newRetryCount >= task.getMaxRetries()) {
                task.setStatus(BenefitTaskStatus.FAILED);
                redisTemplate.opsForList().rightPush(DEAD_LETTER_KEY, message);
                log.error("任务已失败，达到最大重试次数: taskId={}, error={}", 
                        message.getTaskId(), errorMessage);
            } else {
                task.setStatus(BenefitTaskStatus.RETRYING);
                task.setNextRetryAt(java.time.LocalDateTime.now().plusMinutes(newRetryCount * 5));
                message.setRetryCount(newRetryCount);
                redisTemplate.opsForList().rightPush(QUEUE_KEY, message);
                log.warn("任务处理失败，将重试: taskId={}, retryCount={}, error={}", 
                        message.getTaskId(), newRetryCount, errorMessage);
            }
            
            benefitTaskRepository.save(task);
        });
    }
    
    public List<BenefitTask> getPendingTasksFromDB() {
        return benefitTaskRepository.findPendingTasks(
                List.of(BenefitTaskStatus.PENDING, BenefitTaskStatus.RETRYING)
        );
    }
    
    public long getQueueSize() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }
    
    public long getProcessingCount() {
        Long size = redisTemplate.opsForHash().size(PROCESSING_KEY);
        return size != null ? size : 0;
    }
    
    public long getDeadLetterCount() {
        Long size = redisTemplate.opsForList().size(DEAD_LETTER_KEY);
        return size != null ? size : 0;
    }
    
    private String serializeMessage(BenefitTaskMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.warn("序列化任务消息失败", e);
            return message.getTaskId();
        }
    }
    
    public void recoverTasksOnStartup() {
        List<BenefitTask> pendingTasks = getPendingTasksFromDB();
        
        for (BenefitTask task : pendingTasks) {
            try {
                BenefitTaskMessage message = BenefitTaskMessage.builder()
                        .taskId(task.getTaskId())
                        .memberId(task.getMemberId())
                        .levelId(task.getLevelId())
                        .retryCount(task.getRetryCount())
                        .createdAt(task.getCreatedAt())
                        .source("recovery")
                        .build();
                
                redisTemplate.opsForList().rightPush(QUEUE_KEY, message);
                task.setStatus(BenefitTaskStatus.PENDING);
                benefitTaskRepository.save(task);
                
                log.info("恢复待处理任务: taskId={}", task.getTaskId());
            } catch (Exception e) {
                log.error("恢复任务失败: taskId={}", task.getTaskId(), e);
            }
        }
        
        log.info("服务启动恢复任务完成: 恢复{}个任务", pendingTasks.size());
    }
}
