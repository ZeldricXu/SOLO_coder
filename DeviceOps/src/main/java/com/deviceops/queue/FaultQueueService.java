package com.deviceops.queue;

import com.deviceops.config.properties.DeviceOpsProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class FaultQueueService {

    @Autowired
    private DeviceOpsProperties properties;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private final ConcurrentLinkedQueue<FaultTaskDTO> memoryFallbackQueue = new ConcurrentLinkedQueue<>();
    
    private boolean redisAvailable = true;

    private String faultQueueKey;
    private String processingSetKey;
    private String deadLetterKey;

    @PostConstruct
    public void init() {
        objectMapper.registerModule(new JavaTimeModule());
        faultQueueKey = properties.getQueue().getRedisQueueKey();
        processingSetKey = properties.getQueue().getProcessingSetKey();
        deadLetterKey = properties.getQueue().getDeadLetterKey();
        checkRedisAvailability();
    }

    private void checkRedisAvailability() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            redisAvailable = true;
        } catch (Exception e) {
            redisAvailable = false;
            System.err.println("Redis不可用，使用内存队列作为后备: " + e.getMessage());
        }
    }

    public void enqueueFaultTask(FaultTaskDTO task) {
        try {
            String json = objectMapper.writeValueAsString(task);
            if (redisAvailable) {
                redisTemplate.opsForList().rightPush(faultQueueKey, json);
            } else {
                memoryFallbackQueue.offer(task);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化故障任务失败", e);
        }
    }

    public FaultTaskDTO dequeueFaultTask() {
        try {
            if (redisAvailable) {
                String json = redisTemplate.opsForList().leftPop(faultQueueKey);
                if (json != null) {
                    FaultTaskDTO task = objectMapper.readValue(json, FaultTaskDTO.class);
                    markProcessing(task.getFaultId(), json);
                    return task;
                }
            } else {
                return memoryFallbackQueue.poll();
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("反序列化故障任务失败", e);
        } catch (Exception e) {
            redisAvailable = false;
            return memoryFallbackQueue.poll();
        }
        return null;
    }

    private void markProcessing(String faultId, String json) {
        try {
            redisTemplate.opsForSet().add(processingSetKey, faultId);
        } catch (Exception e) {
        }
    }

    public void acknowledgeProcessing(String faultId) {
        try {
            if (redisAvailable) {
                redisTemplate.opsForSet().remove(processingSetKey, faultId);
            }
        } catch (Exception e) {
        }
    }

    public void moveToDeadLetter(FaultTaskDTO task) {
        try {
            if (redisAvailable) {
                String json = objectMapper.writeValueAsString(task);
                redisTemplate.opsForList().rightPush(deadLetterKey, json);
                redisTemplate.opsForSet().remove(processingSetKey, task.getFaultId());
            }
        } catch (Exception e) {
        }
    }

    public long getQueueSize() {
        try {
            if (redisAvailable) {
                Long size = redisTemplate.opsForList().size(faultQueueKey);
                return size != null ? size : 0;
            } else {
                return memoryFallbackQueue.size();
            }
        } catch (Exception e) {
            return memoryFallbackQueue.size();
        }
    }

    public long getProcessingCount() {
        try {
            if (redisAvailable) {
                Long size = redisTemplate.opsForSet().size(processingSetKey);
                return size != null ? size : 0;
            }
        } catch (Exception e) {
        }
        return 0;
    }

    public List<FaultTaskDTO> recoverProcessingTasks() {
        List<FaultTaskDTO> recovered = new ArrayList<>();
        try {
            if (redisAvailable) {
                Long size = redisTemplate.opsForList().size(faultQueueKey);
                if (size != null && size > 0) {
                    List<String> items = redisTemplate.opsForList().range(faultQueueKey, 0, size - 1);
                    if (items != null) {
                        for (String json : items) {
                            try {
                                recovered.add(objectMapper.readValue(json, FaultTaskDTO.class));
                            } catch (Exception e) {
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        return recovered;
    }

    public boolean isUsingRedis() {
        return redisAvailable;
    }
}
