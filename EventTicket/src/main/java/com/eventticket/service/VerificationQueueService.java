package com.eventticket.service;

import com.eventticket.config.VerificationRetryConfig;
import com.eventticket.entity.Event;
import com.eventticket.entity.VerificationConfirmationTask;
import com.eventticket.repository.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VerificationQueueService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private VerificationRetryConfig retryConfig;

    @Autowired
    private EventRepository eventRepository;

    @Value("${verification-queue.name:eventticket:verification:confirmations}")
    private String queueName;

    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public void enqueueConfirmationTask(String ticketId, String eventId, String seatId, String operator) {
        try {
            int maxRetries = 3;
            int retryDelaySeconds = 3;
            int backoffMultiplier = 2;

            Event event = eventRepository.findById(eventId).orElse(null);
            if (event != null) {
                maxRetries = retryConfig.getMaxRetries(event.getEventCapacity());
                retryDelaySeconds = retryConfig.getRetryDelaySeconds(event.getEventCapacity());
                backoffMultiplier = retryConfig.getBackoffMultiplier(event.getEventCapacity());
            }

            VerificationConfirmationTask task = VerificationConfirmationTask.create(
                ticketId, eventId, seatId, operator,
                maxRetries, retryDelaySeconds, backoffMultiplier
            );

            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().rightPush(queueName, taskJson);

            log.info("Enqueued verification confirmation task: taskId={}, ticketId={}", 
                    task.getTaskId(), ticketId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize verification confirmation task", e);
            throw new RuntimeException("Failed to enqueue verification task", e);
        }
    }

    public VerificationConfirmationTask dequeueConfirmationTask(long timeout, TimeUnit unit) {
        try {
            String taskJson = redisTemplate.opsForList()
                    .leftPop(queueName, timeout, unit);
            if (taskJson == null) {
                return null;
            }
            return objectMapper.readValue(taskJson, VerificationConfirmationTask.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize verification confirmation task", e);
            return null;
        }
    }

    public void enqueueRetryTask(VerificationConfirmationTask task) {
        try {
            task.incrementRetry();
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().rightPush(queueName, taskJson);
            log.info("Enqueued retry verification task: taskId={}, retryCount={}", 
                    task.getTaskId(), task.getRetryCount());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize retry task", e);
        }
    }

    public void moveToDeadLetterQueue(VerificationConfirmationTask task, String errorMessage) {
        try {
            task.markFailed();
            String dlqName = queueName + ":dlq";
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().rightPush(dlqName, taskJson);
            log.warn("Moved task to dead letter queue: taskId={}, error={}", 
                    task.getTaskId(), errorMessage);
        } catch (JsonProcessingException e) {
            log.error("Failed to move task to dead letter queue", e);
        }
    }

    public long getQueueSize() {
        Long size = redisTemplate.opsForList().size(queueName);
        return size != null ? size : 0;
    }

    public long getDeadLetterQueueSize() {
        String dlqName = queueName + ":dlq";
        Long size = redisTemplate.opsForList().size(dlqName);
        return size != null ? size : 0;
    }

    public void clearQueue() {
        redisTemplate.delete(queueName);
    }

    public void clearDeadLetterQueue() {
        String dlqName = queueName + ":dlq";
        redisTemplate.delete(dlqName);
    }
}
