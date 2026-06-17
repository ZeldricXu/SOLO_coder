package com.enterprise.risk.storage.redis;

import com.enterprise.risk.common.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitCache {

    private final RedissonClient redissonClient;

    private static final String RATE_LIMIT_PREFIX = "risk:ratelimit:";
    private final Map<String, RateLimitConfig> configCache = new ConcurrentHashMap<>();

    /**
     * 限流配置
     */
    public record RateLimitConfig(
            String name,
            long rate,
            long intervalSeconds,
            RateType rateType
    ) {}

    /**
     * 限流级别
     */
    public enum RateLimitLevel {
        GLOBAL("global", "全局限流"),
        BUSINESS_LINE("business", "业务线限流"),
        ENTITY("entity", "实休限流"),
        IP("ip", "IP限流"),
        USER("user", "用户限流"),
        RULE("rule", "规则限流");

        private final String code;
        private final String description;

        RateLimitLevel(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() {
            return code;
        }
    }

    /**
     * 注册限流配置
     *
     * @param name     配置名称
     * @param rate     速率（请求数）
     * @param interval 时间间隔
     * @param unit     时间单位
     * @param rateType 限流类型（OVERALL或PER_CLIENT）
     */
    public void registerConfig(String name, long rate, long interval,
                               TimeUnit unit, RateType rateType) {
        long intervalSeconds = unit.toSeconds(interval);
        RateLimitConfig config = new RateLimitConfig(name, rate, intervalSeconds, rateType);
        configCache.put(name, config);

        String key = buildConfigKey(name);
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        limiter.trySetRate(rateType, rate, intervalSeconds, RateIntervalUnit.SECONDS);
        limiter.expire(intervalSeconds * 10, TimeUnit.SECONDS);

        log.info("注册限流配置: name={}, rate={}/{}s, type={}", name, rate, intervalSeconds, rateType);
    }

    /**
     * 尝试获取令牌，不抛异常
     *
     * @param name   配置名称
     * @param permits 需要的令牌数
     * @return 是否允许通过
     */
    public boolean tryAcquire(String name, long permits) {
        RateLimitConfig config = configCache.get(name);
        if (config == null) {
            return true;
        }

        String key = buildConfigKey(name);
        RRateLimiter limiter = redissonClient.getRateLimiter(key);

        if (!limiter.isExists()) {
            limiter.trySetRate(config.rateType(), config.rate(),
                    config.intervalSeconds(), RateIntervalUnit.SECONDS);
        }

        return limiter.tryAcquire(permits);
    }

    /**
     * 尝试获取单个令牌
     *
     * @param name 配置名称
     * @return 是否允许通过
     */
    public boolean tryAcquire(String name) {
        return tryAcquire(name, 1);
    }

    /**
     * 获取令牌，超过限制抛出异常
     *
     * @param name   配置名称
     * @param permits 需要的令牌数
     * @throws RateLimitExceededException 超过限流时抛出
     */
    public void acquire(String name, long permits) {
        if (!tryAcquire(name, permits)) {
            RateLimitConfig config = configCache.get(name);
            throw new RateLimitExceededException(
                    "限流规则触发: " + name + ", 限制: " + config.rate() + "/" + config.intervalSeconds() + "s"
            );
        }
    }

    /**
     * 获取单个令牌，超过限制抛出异常
     *
     * @param name 配置名称
     */
    public void acquire(String name) {
        acquire(name, 1);
    }

    /**
     * 按级别和业务键进行限流检查
     *
     * @param level    限流级别
     * @param key      业务键
     * @param rate     速率
     * @param interval 间隔
     * @param unit     时间单位
     * @return 是否允许通过
     */
    public boolean checkRateLimit(RateLimitLevel level, String key,
                                  long rate, long interval, TimeUnit unit) {
        String name = level.getCode() + ":" + key;
        if (!configCache.containsKey(name)) {
            registerConfig(name, rate, interval, unit, RateType.OVERALL);
        }
        return tryAcquire(name);
    }

    /**
     * 按级别和业务键进行限流，超过限制抛出异常
     *
     * @param level    限流级别
     * @param key      业务键
     * @param rate     速率
     * @param interval 间隔
     * @param unit     时间单位
     */
    public void enforceRateLimit(RateLimitLevel level, String key,
                                 long rate, long interval, TimeUnit unit) {
        String name = level.getCode() + ":" + key;
        if (!configCache.containsKey(name)) {
            registerConfig(name, rate, interval, unit, RateType.OVERALL);
        }
        acquire(name);
    }

    /**
     * 获取当前剩余可用令牌数
     *
     * @param name 配置名称
     * @return 剩余令牌数（-1表示未配置）
     */
    public long getAvailablePermits(String name) {
        RateLimitConfig config = configCache.get(name);
        if (config == null) {
            return -1;
        }
        String key = buildConfigKey(name);
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        return limiter.availablePermits();
    }

    /**
     * 删除限流配置
     *
     * @param name 配置名称
     */
    public void removeConfig(String name) {
        configCache.remove(name);
        String key = buildConfigKey(name);
        redissonClient.getRateLimiter(key).delete();
        log.info("删除限流配置: {}", name);
    }

    /**
     * 检查配置是否存在
     *
     * @param name 配置名称
     * @return 是否存在
     */
    public boolean hasConfig(String name) {
        return configCache.containsKey(name);
    }

    private String buildConfigKey(String name) {
        return RATE_LIMIT_PREFIX + name;
    }
}
