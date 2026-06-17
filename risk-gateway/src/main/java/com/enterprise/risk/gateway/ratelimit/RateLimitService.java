package com.enterprise.risk.gateway.ratelimit;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.exception.RateLimitExceededException;
import com.enterprise.risk.gateway.config.RateLimitConfig;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流服务
 * 基于Bucket4j + Redis实现多维度限流：
 * 1. 全局限流
 * 2. 按业务线条限流
 * 3. 按IP限流
 * 4. 按实体ID限流
 *
 * 采用令牌桶算法，支持分布式环境下的精准限流
 */
@Slf4j
@Service
public class RateLimitService {

    private final RateLimitConfig rateLimitConfig;
    private final RedissonClient redissonClient;
    private final RedisConnectionFactory redisConnectionFactory;

    /**
     * 本地Bucket缓存（非Redis模式下使用）
     */
    private final Map<String, Bucket> localBucketCache = new ConcurrentHashMap<>();

    /**
     * Redis代理管理器（分布式限流模式）
     */
    private volatile LettuceBasedProxyManager<byte[]> proxyManager;

    /**
     * 是否使用Redis分布式限流
     */
    private final boolean useRedis;

    public RateLimitService(RateLimitConfig rateLimitConfig,
                            RedissonClient redissonClient,
                            RedisConnectionFactory redisConnectionFactory) {
        this.rateLimitConfig = rateLimitConfig;
        this.redissonClient = redissonClient;
        this.redisConnectionFactory = redisConnectionFactory;
        this.useRedis = redisConnectionFactory != null;

        log.info("限流服务初始化完成, 模式: {}, 限流启用: {}",
                useRedis ? "Redis分布式" : "本地内存",
                rateLimitConfig.isEnabled());
    }

    /**
     * 对事件执行多维度限流检查
     * 所有维度都必须通过，否则抛出限流异常
     *
     * @param event 待限流检查的事件
     * @throws RateLimitExceededException 任意维度超限则抛出
     */
    public void checkRateLimit(RiskEvent event) {
        if (!rateLimitConfig.isEnabled()) {
            return;
        }

        checkGlobalLimit();
        checkBusinessLineLimit(event.getBusinessLine());
        checkIpLimit(event.getIp());
        checkEntityLimit(event.getEntityId());
    }

    /**
     * 全局限流检查
     */
    public void checkGlobalLimit() {
        String key = rateLimitConfig.getRedisKeyPrefix() + "global";
        RateLimitConfig.BucketConfig config = rateLimitConfig.getGlobal();

        boolean allowed = tryConsume(key, config);
        if (!allowed) {
            log.warn("全局限流触发, QPS限制: {}", config.getRefillPerSecond());
            throw new RateLimitExceededException(
                    "全局限流：请求频率超过上限 " + config.getRefillPerSecond() + " QPS",
                    Map.of("dimension", "global", "limit", config.getRefillPerSecond())
            );
        }
    }

    /**
     * 按业务线条限流检查
     */
    public void checkBusinessLineLimit(String businessLine) {
        if (businessLine == null || businessLine.isEmpty()) {
            return;
        }

        String key = rateLimitConfig.getRedisKeyPrefix() + "bl:" + businessLine;
        RateLimitConfig.BucketConfig config = rateLimitConfig.getBusinessLineConfig(businessLine);

        boolean allowed = tryConsume(key, config);
        if (!allowed) {
            log.warn("业务线条限流触发, businessLine: {}, QPS限制: {}", businessLine, config.getRefillPerSecond());
            throw new RateLimitExceededException(
                    "业务线[" + businessLine + "]限流：请求频率超过上限 " + config.getRefillPerSecond() + " QPS",
                    Map.of("dimension", "business_line", "business_line", businessLine, "limit", config.getRefillPerSecond())
            );
        }
    }

    /**
     * 按IP限流检查
     */
    public void checkIpLimit(String ip) {
        if (ip == null || ip.isEmpty()) {
            return;
        }

        String key = rateLimitConfig.getRedisKeyPrefix() + "ip:" + ip;
        RateLimitConfig.BucketConfig config = rateLimitConfig.getPerIp();

        boolean allowed = tryConsume(key, config);
        if (!allowed) {
            log.warn("IP限流触发, ip: {}, QPS限制: {}", ip, config.getRefillPerSecond());
            throw new RateLimitExceededException(
                    "IP[" + ip + "]限流：请求频率超过上限 " + config.getRefillPerSecond() + " QPS",
                    Map.of("dimension", "ip", "ip", ip, "limit", config.getRefillPerSecond())
            );
        }
    }

    /**
     * 按实体ID限流检查
     */
    public void checkEntityLimit(String entityId) {
        if (entityId == null || entityId.isEmpty()) {
            return;
        }

        String key = rateLimitConfig.getRedisKeyPrefix() + "entity:" + entityId;
        RateLimitConfig.BucketConfig config = rateLimitConfig.getPerEntity();

        boolean allowed = tryConsume(key, config);
        if (!allowed) {
            log.warn("实体ID限流触发, entityId: {}, QPS限制: {}", entityId, config.getRefillPerSecond());
            throw new RateLimitExceededException(
                    "实体[" + entityId + "]限流：请求频率超过上限 " + config.getRefillPerSecond() + " QPS",
                    Map.of("dimension", "entity", "entity_id", entityId, "limit", config.getRefillPerSecond())
            );
        }
    }

    /**
     * 尝试消费令牌
     * 根据配置选择Redis分布式或本地内存模式
     *
     * @param key    限流键
     * @param config 令牌桶配置
     * @return true-消费成功（允许通过），false-消费失败（触发限流）
     */
    private boolean tryConsume(String key, RateLimitConfig.BucketConfig config) {
        if (useRedis) {
            return tryConsumeRedis(key, config);
        } else {
            return tryConsumeLocal(key, config);
        }
    }

    /**
     * 本地内存模式消费令牌
     * 适用于单节点部署场景
     */
    private boolean tryConsumeLocal(String key, RateLimitConfig.BucketConfig config) {
        Bucket bucket = localBucketCache.computeIfAbsent(key, k -> createLocalBucket(config));
        return bucket.tryConsume(1);
    }

    /**
     * Redis分布式模式消费令牌
     * 适用于多节点集群部署场景
     */
    private boolean tryConsumeRedis(String key, RateLimitConfig.BucketConfig config) {
        try {
            LettuceBasedProxyManager<byte[]> pm = getProxyManager();
            byte[] redisKey = key.getBytes(StandardCharsets.UTF_8);

            BucketConfiguration bucketConfig = createBucketConfiguration(config);
            Bucket bucket = pm.builder()
                    .build(redisKey, () -> bucketConfig);

            return bucket.tryConsume(1);

        } catch (Exception e) {
            log.error("Redis限流操作异常, key: {}, 降级为本地限流", key, e);
            return tryConsumeLocal(key, config);
        }
    }

    /**
     * 创建本地Bucket实例
     */
    private Bucket createLocalBucket(RateLimitConfig.BucketConfig config) {
        Refill refill = Refill.intervally(config.getRefillPerSecond(), Duration.ofSeconds(1));
        Bandwidth limit = Bandwidth.classic(config.getCapacity(), refill);
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    /**
     * 创建Bucket4j配置对象
     */
    private BucketConfiguration createBucketConfiguration(RateLimitConfig.BucketConfig config) {
        Refill refill = Refill.intervally(config.getRefillPerSecond(), Duration.ofSeconds(1));
        Bandwidth limit = Bandwidth.classic(config.getCapacity(), refill);
        return BucketConfiguration.builder()
                .addLimit(limit)
                .build();
    }

    /**
     * 懒初始化Redis代理管理器
     */
    private LettuceBasedProxyManager<byte[]> getProxyManager() {
        if (proxyManager == null) {
            synchronized (this) {
                if (proxyManager == null) {
                    try {
                        Object lettuceConnFactory = redisConnectionFactory;
                        proxyManager = LettuceBasedProxyManager.builderFor(lettuceConnFactory)
                                .withExpirationAfterWrite(Duration.ofHours(24))
                                .build();
                        log.info("Bucket4j Redis代理管理器初始化成功");
                    } catch (Exception e) {
                        log.error("Bucket4j Redis代理管理器初始化失败，将使用本地限流", e);
                        throw new RuntimeException("Redis proxy manager init failed", e);
                    }
                }
            }
        }
        return proxyManager;
    }

    /**
     * 查询指定维度当前剩余令牌数（用于监控）
     *
     * @param key 限流键
     * @return 剩余可用令牌数，-1表示查询失败
     */
    public long getAvailableTokens(String key) {
        try {
            if (useRedis) {
                LettuceBasedProxyManager<byte[]> pm = getProxyManager();
                byte[] redisKey = key.getBytes(StandardCharsets.UTF_8);
                Bucket bucket = pm.getProxy(redisKey);
                if (bucket != null) {
                    return bucket.getAvailableTokens();
                }
            } else {
                Bucket bucket = localBucketCache.get(key);
                if (bucket != null) {
                    return bucket.getAvailableTokens();
                }
            }
        } catch (Exception e) {
            log.warn("查询令牌数失败, key: {}", key, e);
        }
        return -1;
    }
}
