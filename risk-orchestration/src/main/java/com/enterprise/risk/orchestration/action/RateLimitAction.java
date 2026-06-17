package com.enterprise.risk.orchestration.action;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.orchestration.core.ActionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 接口限流动作
 * 通过Redis令牌桶算法对实体ID进行限流，同时通过网关标记通知下游
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitAction implements Action {

    private static final String ACTION_ID = "rate_limit";
    private static final String ACTION_NAME = "接口限流动作";

    /**
     * Redis限流键前缀
     */
    private static final String RATE_LIMIT_KEY_PREFIX = "risk:rate_limit:";

    /**
     * 网关标记键前缀
     */
    private static final String GATEWAY_FLAG_KEY_PREFIX = "risk:gateway:flag:";

    /**
     * 默认限流时长（秒）
     */
    private static final long DEFAULT_DURATION_SECONDS = 300;

    /**
     * 默认限流速率（每秒许可数）
     */
    private static final long DEFAULT_RATE = 1;

    private final RedissonClient redissonClient;

    @Override
    public String getActionId() {
        return ACTION_ID;
    }

    @Override
    public String getActionName() {
        return ACTION_NAME;
    }

    @Override
    public boolean execute(AlertEvent alertEvent, ActionContext context) {
        String entityId = alertEvent.getEntityId();
        if (entityId == null || entityId.isEmpty()) {
            log.warn("[RateLimitAction] 实体ID为空，跳过量流，alertId={}", alertEvent.getAlertId());
            return false;
        }

        Long durationSeconds = context.getParameterOrDefault("duration_seconds", DEFAULT_DURATION_SECONDS);
        Long rate = context.getParameterOrDefault("rate_per_second", DEFAULT_RATE);
        String limitDimension = context.getParameterOrDefault("limit_dimension", "entity_id");

        String limitKey = buildLimitKey(limitDimension, entityId);

        try {
            applyRateLimit(limitKey, rate, durationSeconds);
            markGatewayFlag(entityId, durationSeconds);
            log.info("[RateLimitAction] 限流成功, entityId={}, duration={}s, rate={}/s, alertId={}",
                    entityId, durationSeconds, rate, alertEvent.getAlertId());
            context.saveResult(ACTION_ID, true);
            return true;
        } catch (Exception e) {
            log.error("[RateLimitAction] 限流失败, entityId={}, alertId={}", entityId, alertEvent.getAlertId(), e);
            context.saveResult(ACTION_ID, false);
            return false;
        }
    }

    /**
     * 构建限流键
     */
    private String buildLimitKey(String dimension, String entityId) {
        return RATE_LIMIT_KEY_PREFIX + dimension + ":" + entityId;
    }

    /**
     * 应用令牌桶限流
     */
    private void applyRateLimit(String key, long rate, long durationSeconds) {
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        rateLimiter.trySetRate(RateType.OVERALL, rate, 1, RateIntervalUnit.SECONDS);
        rateLimiter.expire(durationSeconds, TimeUnit.SECONDS);
    }

    /**
     * 标记网关限流标识
     */
    private void markGatewayFlag(String entityId, long durationSeconds) {
        String flagKey = GATEWAY_FLAG_KEY_PREFIX + entityId;
        redissonClient.getBucket(flagKey).set(true, durationSeconds, TimeUnit.SECONDS);
    }
}
