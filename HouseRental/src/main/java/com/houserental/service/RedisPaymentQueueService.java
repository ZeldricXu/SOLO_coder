package com.houserental.service;

import com.houserental.entity.PaymentTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class RedisPaymentQueueService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${payment.queue.redis-key:rental:payment:tasks}")
    private String taskQueueKey;

    @Value("${payment.queue.retry-key:rental:payment:retry}")
    private String retryQueueKey;

    @Value("${payment.queue.dead-letter-key:rental:payment:dead-letter}")
    private String deadLetterKey;

    public void enqueuePaymentTask(PaymentTask task) {
        redisTemplate.opsForList().rightPush(taskQueueKey, task);
    }

    public PaymentTask dequeuePaymentTask() {
        Object task = redisTemplate.opsForList().leftPop(taskQueueKey, 5, TimeUnit.SECONDS);
        return task instanceof PaymentTask ? (PaymentTask) task : null;
    }

    public void enqueueRetryTask(PaymentTask task) {
        String key = retryQueueKey + ":" + task.getTaskId();
        redisTemplate.opsForValue().set(key, task, 24, TimeUnit.HOURS);
    }

    public PaymentTask getRetryTask(String taskId) {
        String key = retryQueueKey + ":" + taskId;
        Object task = redisTemplate.opsForValue().get(key);
        return task instanceof PaymentTask ? (PaymentTask) task : null;
    }

    public void removeRetryTask(String taskId) {
        String key = retryQueueKey + ":" + taskId;
        redisTemplate.delete(key);
    }

    public List<PaymentTask> getAllRetryTasks() {
        Set<String> keys = redisTemplate.keys(retryQueueKey + ":*");
        List<PaymentTask> tasks = new ArrayList<>();
        
        if (keys != null) {
            for (String key : keys) {
                Object task = redisTemplate.opsForValue().get(key);
                if (task instanceof PaymentTask) {
                    tasks.add((PaymentTask) task);
                }
            }
        }
        
        return tasks;
    }

    public void moveToDeadLetter(PaymentTask task) {
        redisTemplate.opsForList().rightPush(deadLetterKey, task);
    }

    public long getPendingTaskCount() {
        Long count = redisTemplate.opsForList().size(taskQueueKey);
        return count != null ? count : 0;
    }

    public long getRetryTaskCount() {
        Set<String> keys = redisTemplate.keys(retryQueueKey + ":*");
        return keys != null ? keys.size() : 0;
    }

    public long getDeadLetterCount() {
        Long count = redisTemplate.opsForList().size(deadLetterKey);
        return count != null ? count : 0;
    }

    public void clearAllQueues() {
        redisTemplate.delete(taskQueueKey);
        Set<String> retryKeys = redisTemplate.keys(retryQueueKey + ":*");
        if (retryKeys != null) {
            redisTemplate.delete(retryKeys);
        }
        redisTemplate.delete(deadLetterKey);
    }
}
