package com.scheduler.scheduler.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.stats.CacheStats;
import com.scheduler.persistence.entity.ScheduledTask;
import com.scheduler.persistence.mapper.ScheduledTaskMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@Slf4j
@Component
public class TaskCacheService {

    private final RedissonClient redissonClient;
    private final ScheduledTaskMapper taskMapper;

    @Getter
    private final Cache<String, ScheduledTask> l1Cache;
    private final RMapCache<String, ScheduledTask> l2Cache;

    private static final String L2_CACHE_NAME = "scheduler:tasks";
    private static final int L1_MAX_SIZE = 10000;
    private static final Duration L1_TTL_MINUTES = 10;
    private static final Duration L2_TTL_MINUTES = 30;

    private final AtomicBoolean warmed = new AtomicBoolean(false);
    private final Map<String, Long> lastAccessTime = new ConcurrentHashMap<>();

    public TaskCacheService(RedissonClient redissonClient, ScheduledTaskMapper taskMapper) {
        this.redissonClient = redissonClient;
        this.taskMapper = taskMapper;

        this.l1Cache = Caffeine.newBuilder()
                .maximumSize(L1_MAX_SIZE)
                .expireAfterWrite(L1_TTL_MINUTES, TimeUnit.MINUTES)
                .recordStats()
                .build();

        this.l2Cache = redissonClient.getMapCache(L2_CACHE_NAME);
    }

    @PostConstruct
    public void init() {
        log.info("Task multi-level cache initialized: L1(maxSize={}, ttl={}min), L2(ttl={}min)",
                L1_MAX_SIZE, L1_TTL_MINUTES, L2_TTL_MINUTES);
    }

    public ScheduledTask get(String taskId) {
        lastAccessTime.put(taskId, System.currentTimeMillis());
        ScheduledTask task = l1Cache.getIfPresent(taskId);
        if (task != null) {
            log.debug("L1 cache hit for task: {}", taskId);
            return task;
        }

        task = l2Cache.get(taskId);
        if (task != null) {
            log.debug("L2 cache hit for task: {}", taskId);
            l1Cache.put(taskId, task);
            return task;
        }

        log.debug("Cache miss for task: {}, loading from DB", taskId);
        task = loadFromDB(taskId);
        if (task != null) {
            put(taskId, task);
        }
        return task;
    }

    public ScheduledTask get(String taskId, Function<String, ScheduledTask> loader) {
        lastAccessTime.put(taskId, System.currentTimeMillis());
        ScheduledTask task = get(taskId);
        if (task != null) {
            return task;
        }
        task = loader.apply(taskId);
        if (task != null) {
            put(taskId, task);
        }
        return task;
    }

    public void put(String taskId, ScheduledTask task) {
        l1Cache.put(taskId, task);
        l2Cache.put(taskId, task, L2_TTL_MINUTES, TimeUnit.MINUTES);
        log.debug("Cached task: {} in L1 and L2", taskId);
    }

    public void invalidate(String taskId) {
        l1Cache.invalidate(taskId);
        l2Cache.fastRemove(taskId);
        lastAccessTime.remove(taskId);
        log.debug("Invalidated cache for task: {}", taskId);
    }

    public void invalidateAll() {
        l1Cache.invalidateAll();
        l2Cache.clear();
        lastAccessTime.clear();
        log.info("Invalidated all task cache entries");
    }

    public void warmUp() {
        if (warmed.compareAndSet(false, true)) {
            log.info("Starting task cache warm-up...");
            try {
                List<ScheduledTask> activeTasks = taskMapper.findByStatus("ACTIVE");
                log.info("Found {} active tasks to warm up", activeTasks.size());

                for (ScheduledTask task : activeTasks) {
                    if (task.getTaskId() != null) {
                        put(task.getTaskId(), task);
                    }
                }
                log.info("Task cache warm-up completed, loaded {} tasks", activeTasks.size());
            } catch (Exception e) {
                log.error("Task cache warm-up failed", e);
                warmed.set(false);
            }
        }
    }

    public boolean isWarmed() {
        return warmed.get();
    }

    private ScheduledTask loadFromDB(String taskId) {
        return taskMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ScheduledTask>()
                        .eq(ScheduledTask::getTaskId, taskId)
        );
    }

    public CacheStats getL1Stats() {
        return l1Cache.stats();
    }

    public long getL1Size() {
        return l1Cache.estimatedSize();
    }

    public long getL2Size() {
        return l2Cache.size();
    }

    public double getL1HitRate() {
        CacheStats stats = l1Cache.stats();
        return stats.hitRate();
    }

    public Set<String> getCachedTaskIds() {
        return l1Cache.asMap().keySet();
    }

    public long getLastAccessTime(String taskId) {
        return lastAccessTime.getOrDefault(taskId, 0L);
    }

    public void invalidateExpiredEntries() {
        long cutoff = System.currentTimeMillis() - Duration.ofHours(24).toMillis();
        int count = 0;
        for (Map.Entry<String, Long> entry : lastAccessTime.entrySet()) {
            if (entry.getValue() < cutoff) {
                invalidate(entry.getKey());
                count++;
            }
        }
        if (count > 0) {
            log.info("Evicted {} expired task cache entries", count);
        }
    }
}
