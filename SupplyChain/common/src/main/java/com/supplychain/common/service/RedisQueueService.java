package com.supplychain.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Service
public class RedisQueueService {

    private final Map<String, Queue<Object>> inMemoryQueues = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> inMemorySets = new ConcurrentHashMap<>();
    private final Map<String, Object> inMemoryHash = new ConcurrentHashMap<>();

    public boolean isRedisAvailable() {
        return false;
    }

    public void pushToQueue(String queueKey, Object value) {
        log.debug("推送到队列: queueKey={}, value={}", queueKey, value);
        Queue<Object> queue = inMemoryQueues.computeIfAbsent(queueKey, k -> new ConcurrentLinkedQueue<>());
        queue.offer(value);
    }

    public Object popFromQueue(String queueKey) {
        Queue<Object> queue = inMemoryQueues.get(queueKey);
        if (queue != null) {
            Object value = queue.poll();
            log.debug("从队列弹出: queueKey={}, value={}", queueKey, value);
            return value;
        }
        return null;
    }

    public List<Object> popBatchFromQueue(String queueKey, int count) {
        List<Object> result = new ArrayList<>();
        Queue<Object> queue = inMemoryQueues.get(queueKey);
        if (queue != null) {
            for (int i = 0; i < count && !queue.isEmpty(); i++) {
                result.add(queue.poll());
            }
        }
        log.debug("从队列批量弹出: queueKey={}, count={}", queueKey, result.size());
        return result;
    }

    public long getQueueSize(String queueKey) {
        Queue<Object> queue = inMemoryQueues.get(queueKey);
        return queue != null ? queue.size() : 0;
    }

    public void addToSet(String setKey, String value) {
        Set<String> set = inMemorySets.computeIfAbsent(setKey, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        set.add(value);
        log.debug("添加到集合: setKey={}, value={}", setKey, value);
    }

    public void removeFromSet(String setKey, String value) {
        Set<String> set = inMemorySets.get(setKey);
        if (set != null) {
            set.remove(value);
            log.debug("从集合移除: setKey={}, value={}", setKey, value);
        }
    }

    public boolean isInSet(String setKey, String value) {
        Set<String> set = inMemorySets.get(setKey);
        return set != null && set.contains(value);
    }

    public Set<String> getSetMembers(String setKey) {
        Set<String> set = inMemorySets.get(setKey);
        return set != null ? new HashSet<>(set) : new HashSet<>();
    }

    public void setHashValue(String key, String field, Object value) {
        String hashKey = key + ":" + field;
        inMemoryHash.put(hashKey, value);
        log.debug("设置Hash值: key={}, field={}, value={}", key, field, value);
    }

    public Object getHashValue(String key, String field) {
        String hashKey = key + ":" + field;
        return inMemoryHash.get(hashKey);
    }

    public Map<String, Object> getAllHashValues(String key) {
        Map<String, Object> result = new HashMap<>();
        String prefix = key + ":";
        for (Map.Entry<String, Object> entry : inMemoryHash.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String field = entry.getKey().substring(prefix.length());
                result.put(field, entry.getValue());
            }
        }
        return result;
    }

    public void deleteKey(String key) {
        inMemoryQueues.remove(key);
        inMemorySets.remove(key);
        inMemoryHash.keySet().removeIf(k -> k.startsWith(key + ":"));
        log.debug("删除Key: key={}", key);
    }

    public boolean hasKey(String key) {
        if (inMemoryQueues.containsKey(key)) return true;
        if (inMemorySets.containsKey(key)) return true;
        String prefix = key + ":";
        return inMemoryHash.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }

    public Map<String, Object> getQueueStatistics() {
        Map<String, Object> stats = new HashMap<>();
        Map<String, Long> queueSizes = new HashMap<>();
        for (Map.Entry<String, Queue<Object>> entry : inMemoryQueues.entrySet()) {
            queueSizes.put(entry.getKey(), (long) entry.getValue().size());
        }
        stats.put("queues", queueSizes);

        Map<String, Integer> setSizes = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : inMemorySets.entrySet()) {
            setSizes.put(entry.getKey(), entry.getValue().size());
        }
        stats.put("sets", setSizes);

        stats.put("hashEntries", inMemoryHash.size());
        return stats;
    }

    public void clearAll() {
        inMemoryQueues.clear();
        inMemorySets.clear();
        inMemoryHash.clear();
        log.info("所有Redis模拟数据已清空");
    }
}
