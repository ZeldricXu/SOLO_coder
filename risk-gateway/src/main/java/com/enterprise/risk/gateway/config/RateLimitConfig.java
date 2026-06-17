package com.enterprise.risk.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 限流配置类
 * 基于令牌桶算法，配置多维度限流参数：
 * - 全局QPS
 * - 按业务线QPS
 * - 按IP QPS
 * - 按实体ID QPS
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "risk.gateway.rate-limit")
public class RateLimitConfig {

    /**
     * 是否启用限流
     */
    private boolean enabled = true;

    /**
     * 全局限流配置（令牌桶参数）
     */
    private BucketConfig global = new BucketConfig(10000, 10000);

    /**
     * 按业务线条限流配置
     * key: 业务线编码（如payment、login等）
     */
    private Map<String, BucketConfig> businessLine = new HashMap<>();

    /**
     * 按IP限流配置
     */
    private BucketConfig perIp = new BucketConfig(1000, 1000);

    /**
     * 按实体ID限流配置
     */
    private BucketConfig perEntity = new BucketConfig(500, 500);

    /**
     * Redis键前缀
     */
    private String redisKeyPrefix = "risk:ratelimit:";

    /**
     * 令牌桶配置内部类
     */
    @Data
    public static class BucketConfig {

        /**
         * 令牌桶容量（最大突发请求数）
         */
        private long capacity;

        /**
         * 每秒生成令牌数（QPS）
         */
        private long refillPerSecond;

        public BucketConfig() {
        }

        public BucketConfig(long capacity, long refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
        }
    }

    /**
     * 获取指定业务线的限流配置，不存在则返回默认配置
     */
    public BucketConfig getBusinessLineConfig(String businessLine) {
        return this.businessLine.getOrDefault(businessLine, new BucketConfig(2000, 2000));
    }
}
