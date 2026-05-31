package com.solocoder.platform.logging.cache;

import com.solocoder.platform.logging.model.LogLevelConfig;
import com.solocoder.platform.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
public class L2DistributedCache {

    private static final String KEY_PREFIX = "log:level:";
    private static final String SET_KEY = "log:level:keys";

    private final StringRedisTemplate redisTemplate;
    private final Duration defaultTtl;

    public L2DistributedCache(StringRedisTemplate redisTemplate, Duration defaultTtl) {
        this.redisTemplate = redisTemplate;
        this.defaultTtl = defaultTtl;
        log.info("L2 distributed cache initialized: defaultTtl={}", defaultTtl);
    }

    public Optional<LogLevelConfig> get(String loggerName) {
        try {
            String key = KEY_PREFIX + loggerName;
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                LogLevelConfig config = JsonUtils.fromJson(value, LogLevelConfig.class);
                if (config.isExpired()) {
                    redisTemplate.delete(key);
                    redisTemplate.opsForSet().remove(SET_KEY, loggerName);
                    log.debug("L2 cache entry expired and removed: logger={}", loggerName);
                    return Optional.empty();
                }
                log.debug("L2 cache hit: logger={}", loggerName);
                return Optional.of(config);
            }
            log.debug("L2 cache miss: logger={}", loggerName);
            return Optional.empty();
        } catch (Exception e) {
            log.error("L2 cache get failed: logger={}", loggerName, e);
            return Optional.empty();
        }
    }

    public void put(String loggerName, LogLevelConfig config) {
        try {
            String key = KEY_PREFIX + loggerName;
            Duration ttl = config.getTtlSeconds() > 0
                    ? Duration.ofSeconds(config.getTtlSeconds())
                    : defaultTtl;
            String value = JsonUtils.toJson(config);
            redisTemplate.opsForValue().set(key, value, ttl);
            redisTemplate.opsForSet().add(SET_KEY, loggerName);
            log.debug("L2 cache put: logger={}, level={}, ttl={}", loggerName, config.getLevel(), ttl);
        } catch (Exception e) {
            log.error("L2 cache put failed: logger={}", loggerName, e);
        }
    }

    public void invalidate(String loggerName) {
        try {
            String key = KEY_PREFIX + loggerName;
            redisTemplate.delete(key);
            redisTemplate.opsForSet().remove(SET_KEY, loggerName);
            log.debug("L2 cache invalidated: logger={}", loggerName);
        } catch (Exception e) {
            log.error("L2 cache invalidate failed: logger={}", loggerName, e);
        }
    }

    public void invalidateAll() {
        try {
            Set<String> keys = redisTemplate.opsForSet().members(SET_KEY);
            if (keys != null && !keys.isEmpty()) {
                keys.forEach(k -> redisTemplate.delete(KEY_PREFIX + k));
                redisTemplate.delete(SET_KEY);
            }
            log.debug("L2 cache cleared all entries");
        } catch (Exception e) {
            log.error("L2 cache invalidateAll failed", e);
        }
    }

    public Map<String, LogLevelConfig> getAll() {
        Map<String, LogLevelConfig> result = new HashMap<>();
        try {
            Set<String> keys = redisTemplate.opsForSet().members(SET_KEY);
            if (keys != null) {
                for (String loggerName : keys) {
                    get(loggerName).ifPresent(config -> result.put(loggerName, config));
                }
            }
        } catch (Exception e) {
            log.error("L2 cache getAll failed", e);
        }
        return result;
    }

    public void publishInvalidation(String loggerName) {
        try {
            String channel = "log:level:invalidate";
            redisTemplate.convertAndSend(channel, loggerName);
            log.debug("Published invalidation event: logger={}", loggerName);
        } catch (Exception e) {
            log.error("Failed to publish invalidation event: logger={}", loggerName, e);
        }
    }
}
