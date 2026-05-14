package com.iotconnect.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class RedisPersistentQueue<T> {

    private static final Logger logger = LoggerFactory.getLogger(RedisPersistentQueue.class);
    
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    
    private static final String QUEUE_KEY_PREFIX = "iot:alert:queue:";
    private static final String PROCESSING_KEY_PREFIX = "iot:alert:processing:";
    private static final String DEFAULT_QUEUE_NAME = "detection";
    private static final long LOCK_TIMEOUT_SECONDS = 300;

    public RedisPersistentQueue(StringRedisTemplate stringRedisTemplate,
                                 RedisTemplate<String, Object> redisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() {
        logger.info("RedisPersistentQueue initialized with prefix: {}", QUEUE_KEY_PREFIX);
        recoverInProgressItems();
    }

    public String enqueue(T item) {
        return enqueue(DEFAULT_QUEUE_NAME, item);
    }

    public String enqueue(String queueName, T item) {
        String itemId = UUID.randomUUID().toString();
        String queueKey = getQueueKey(queueName);
        String dataKey = getItemDataKey(itemId);

        try {
            String itemJson = objectMapper.writeValueAsString(item);
            redisTemplate.opsForValue().set(dataKey, itemJson);
            stringRedisTemplate.opsForList().rightPush(queueKey, itemId);
            
            logger.debug("Item enqueued: queue={}, itemId={}", queueName, itemId);
            return itemId;

        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize item for queue: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to enqueue item", e);
        }
    }

    public QueueItem<T> dequeue(long timeout, TimeUnit unit) {
        return dequeue(DEFAULT_QUEUE_NAME, timeout, unit);
    }

    public QueueItem<T> dequeue(String queueName, long timeout, TimeUnit unit) {
        String queueKey = getQueueKey(queueName);
        
        String itemId = stringRedisTemplate.opsForList().leftPop(queueKey, timeout, unit);
        
        if (itemId == null) {
            return null;
        }

        String dataKey = getItemDataKey(itemId);
        String processingKey = getProcessingKey(itemId);

        try {
            Object itemJson = redisTemplate.opsForValue().get(dataKey);
            
            if (itemJson == null) {
                logger.warn("Item data not found for itemId: {}", itemId);
                return null;
            }

            T item = deserializeItem(itemJson.toString());
            
            ProcessingInfo processingInfo = new ProcessingInfo(
                    itemId,
                    System.currentTimeMillis(),
                    LOCK_TIMEOUT_SECONDS
            );
            
            redisTemplate.opsForValue().set(processingKey, 
                    objectMapper.writeValueAsString(processingInfo));

            logger.debug("Item dequeued for processing: queue={}, itemId={}", queueName, itemId);
            return new QueueItem<>(itemId, item);

        } catch (Exception e) {
            logger.error("Failed to deserialize item: itemId={}, error={}", itemId, e.getMessage(), e);
            returnItemToQueue(queueName, itemId);
            return null;
        }
    }

    public QueueItem<T> dequeue() {
        return dequeue(DEFAULT_QUEUE_NAME, 5, TimeUnit.SECONDS);
    }

    public void acknowledge(String itemId) {
        String dataKey = getItemDataKey(itemId);
        String processingKey = getProcessingKey(itemId);
        
        redisTemplate.delete(dataKey);
        redisTemplate.delete(processingKey);
        
        logger.debug("Item acknowledged: itemId={}", itemId);
    }

    public void nack(String itemId, boolean requeue) {
        nack(DEFAULT_QUEUE_NAME, itemId, requeue);
    }

    public void nack(String queueName, String itemId, boolean requeue) {
        String processingKey = getProcessingKey(itemId);
        redisTemplate.delete(processingKey);

        if (requeue) {
            returnItemToQueue(queueName, itemId);
            logger.info("Item returned to queue: itemId={}", itemId);
        } else {
            String dataKey = getItemDataKey(itemId);
            redisTemplate.delete(dataKey);
            logger.info("Item discarded after nack: itemId={}", itemId);
        }
    }

    private void returnItemToQueue(String queueName, String itemId) {
        String queueKey = getQueueKey(queueName);
        stringRedisTemplate.opsForList().leftPush(queueKey, itemId);
    }

    public long size() {
        return size(DEFAULT_QUEUE_NAME);
    }

    public long size(String queueName) {
        String queueKey = getQueueKey(queueName);
        Long size = stringRedisTemplate.opsForList().size(queueKey);
        return size != null ? size : 0;
    }

    public long processingCount() {
        Set<String> keys = redisTemplate.keys(PROCESSING_KEY_PREFIX + "*");
        return keys != null ? keys.size() : 0;
    }

    public void clear() {
        clear(DEFAULT_QUEUE_NAME);
    }

    public void clear(String queueName) {
        String queueKey = getQueueKey(queueName);
        
        List<String> itemIds = stringRedisTemplate.opsForList().range(queueKey, 0, -1);
        if (itemIds != null) {
            for (String itemId : itemIds) {
                redisTemplate.delete(getItemDataKey(itemId));
                redisTemplate.delete(getProcessingKey(itemId));
            }
        }
        
        redisTemplate.delete(queueKey);
        
        logger.info("Queue cleared: queueName={}", queueName);
    }

    public boolean isEmpty() {
        return isEmpty(DEFAULT_QUEUE_NAME);
    }

    public boolean isEmpty(String queueName) {
        return size(queueName) == 0;
    }

    private void recoverInProgressItems() {
        Set<String> processingKeys = redisTemplate.keys(PROCESSING_KEY_PREFIX + "*");
        
        if (processingKeys == null || processingKeys.isEmpty()) {
            logger.info("No in-progress items to recover");
            return;
        }

        List<String> recoveredItems = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (String processingKey : processingKeys) {
            try {
                Object infoJson = redisTemplate.opsForValue().get(processingKey);
                if (infoJson == null) {
                    continue;
                }

                ProcessingInfo info = objectMapper.readValue(infoJson.toString(), ProcessingInfo.class);
                
                long elapsedSeconds = (now - info.getStartTime()) / 1000;
                
                if (elapsedSeconds >= info.getTimeoutSeconds()) {
                    String itemId = info.getItemId();
                    returnItemToQueue(DEFAULT_QUEUE_NAME, itemId);
                    redisTemplate.delete(processingKey);
                    recoveredItems.add(itemId);
                    logger.warn("Recovered expired item: itemId={}, elapsed={}s", itemId, elapsedSeconds);
                }

            } catch (Exception e) {
                logger.warn("Failed to recover processing item: key={}, error={}", 
                        processingKey, e.getMessage());
            }
        }

        if (!recoveredItems.isEmpty()) {
            logger.info("Recovered {} items from previous processing session: {}", 
                    recoveredItems.size(), recoveredItems);
        } else {
            logger.info("No expired items to recover");
        }
    }

    private String getQueueKey(String queueName) {
        return QUEUE_KEY_PREFIX + queueName;
    }

    private String getItemDataKey(String itemId) {
        return "iot:alert:data:" + itemId;
    }

    private String getProcessingKey(String itemId) {
        return PROCESSING_KEY_PREFIX + itemId;
    }

    @SuppressWarnings("unchecked")
    private T deserializeItem(String json) throws JsonProcessingException {
        return (T) objectMapper.readValue(json, Object.class);
    }

    public static class QueueItem<T> {
        private final String itemId;
        private final T item;

        public QueueItem(String itemId, T item) {
            this.itemId = itemId;
            this.item = item;
        }

        public String getItemId() {
            return itemId;
        }

        public T getItem() {
            return item;
        }
    }

    public static class ProcessingInfo {
        private String itemId;
        private long startTime;
        private long timeoutSeconds;

        public ProcessingInfo() {
        }

        public ProcessingInfo(String itemId, long startTime, long timeoutSeconds) {
            this.itemId = itemId;
            this.startTime = startTime;
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getItemId() {
            return itemId;
        }

        public void setItemId(String itemId) {
            this.itemId = itemId;
        }

        public long getStartTime() {
            return startTime;
        }

        public void setStartTime(long startTime) {
            this.startTime = startTime;
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
