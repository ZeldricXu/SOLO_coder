package com.taskflow.gateway.api;

import reactor.core.publisher.Mono;

/**
 * 限流服务 - 最小化接口
 * 仅定义限流相关的核心操作
 */
public interface RateLimitService {

    /**
     * 检查是否允许请求通过
     * @param key 限流键（如tenantId、userId、ip等）
     * @param limit 每秒限制请求数
     * @return 是否允许通过
     */
    Mono<Boolean> tryAcquire(String key, int limit);

    /**
     * 获取剩余可用请求数
     * @param key 限流键
     * @param limit 每秒限制请求数
     * @return 剩余请求数
     */
    Mono<Long> getRemaining(String key, int limit);

    /**
     * 重置限流计数
     * @param key 限流键
     */
    void reset(String key);
}
