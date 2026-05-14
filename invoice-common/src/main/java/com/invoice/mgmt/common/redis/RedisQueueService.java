package com.invoice.mgmt.common.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class RedisQueueService {
    private static final Logger logger = LoggerFactory.getLogger(RedisQueueService.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${redis.queue.prefix:invoice:queue:}")
    private String queuePrefix;

    @Value("${redis.queue.poll.timeout:1000}")
    private long pollTimeoutMs;

    @Value("${redis.queue.max-poll:100}")
    private int maxPollMessages;

    private final Map<String, QueueConsumer<?>> consumers = new ConcurrentHashMap<>();
    private ExecutorService executorService;

    @PostConstruct
    public void init() {
        executorService = Executors.newCachedThreadPool();
        logger.info("Redis队列服务初始化完成");
    }

    @PreDestroy
    public void destroy() {
        logger.info("Redis队列服务停止中...");
        consumers.values().forEach(QueueConsumer::stop);
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("Redis队列服务已停止");
    }

    private String getQueueKey(String queueName) {
        return queuePrefix + queueName;
    }

    public <T> boolean push(String queueName, T item) {
        try {
            String json = serialize(item);
            redisTemplate.opsForList().rightPush(getQueueKey(queueName), json);
            logger.debug("消息已入队: queue={}", queueName);
            return true;
        } catch (Exception e) {
            logger.error("消息入队失败: queue={}", queueName, e);
            return false;
        }
    }

    public <T> T pop(String queueName, Class<T> clazz) {
        String json = redisTemplate.opsForList().leftPop(getQueueKey(queueName));
        if (json == null) {
            return null;
        }
        try {
            return deserialize(json, clazz);
        } catch (Exception e) {
            logger.error("消息反序列化失败: queue={}", queueName, e);
            return null;
        }
    }

    public <T> T bPop(String queueName, Class<T> clazz, long timeoutMs) {
        List<String> result = redisTemplate.opsForList().leftPop(
                getQueueKey(queueName), timeoutMs, TimeUnit.MILLISECONDS);
        if (result == null || result.isEmpty()) {
            return null;
        }
        try {
            return deserialize(result.get(0), clazz);
        } catch (Exception e) {
            logger.error("消息反序列化失败: queue={}", queueName, e);
            return null;
        }
    }

    public long size(String queueName) {
        Long size = redisTemplate.opsForList().size(getQueueKey(queueName));
        return size != null ? size : 0;
    }

    public boolean isEmpty(String queueName) {
        return size(queueName) == 0;
    }

    public <T> List<T> range(String queueName, long start, long end, Class<T> clazz) {
        List<String> items = redisTemplate.opsForList().range(getQueueKey(queueName), start, end);
        if (items == null) {
            return Collections.emptyList();
        }
        return items.stream()
                .map(json -> {
                    try {
                        return deserialize(json, clazz);
                    } catch (Exception e) {
                        logger.error("消息反序列化失败: queue={}", queueName, e);
                        return null;
                    }
                })
                .filter(item -> item != null)
                .collect(java.util.stream.Collectors.toList());
    }

    public boolean clear(String queueName) {
        redisTemplate.delete(getQueueKey(queueName));
        return true;
    }

    public <T> void registerConsumer(String queueName, Class<T> clazz, Consumer<T> handler) {
        QueueConsumer<T> consumer = new QueueConsumer<>(queueName, clazz, handler, this);
        consumers.put(queueName, consumer);
        executorService.submit(consumer);
        logger.info("已注册消费者: queue={}", queueName);
    }

    public void unregisterConsumer(String queueName) {
        QueueConsumer<?> consumer = consumers.remove(queueName);
        if (consumer != null) {
            consumer.stop();
            logger.info("已注销消费者: queue={}", queueName);
        }
    }

    private <T> String serialize(T item) throws JsonProcessingException {
        return objectMapper.writeValueAsString(item);
    }

    private <T> T deserialize(String json, Class<T> clazz) throws JsonProcessingException {
        return objectMapper.readValue(json, clazz);
    }

    private static class QueueConsumer<T> implements Runnable {
        private final String queueName;
        private final Class<T> clazz;
        private final Consumer<T> handler;
        private final RedisQueueService queueService;
        private volatile boolean running = true;

        public QueueConsumer(String queueName, Class<T> clazz, Consumer<T> handler, RedisQueueService queueService) {
            this.queueName = queueName;
            this.clazz = clazz;
            this.handler = handler;
            this.queueService = queueService;
        }

        public void stop() {
            running = false;
        }

        @Override
        public void run() {
            logger.info("队列消费者启动: queue={}", queueName);
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    T item = queueService.bPop(queueName, clazz, queueService.pollTimeoutMs);
                    if (item != null) {
                        try {
                            handler.accept(item);
                        } catch (Exception e) {
                            logger.error("处理消息失败: queue={}", queueName, e);
                        }
                    }
                } catch (Exception e) {
                    logger.error("队列消费异常: queue={}", queueName, e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            logger.info("队列消费者停止: queue={}", queueName);
        }
    }
}
