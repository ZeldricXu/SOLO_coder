package com.delivery.tracker.notification;

import com.delivery.tracker.entity.Notification;

/**
 * 通知发送策略接口
 * 定义可插拔的通知发送策略，支持运行时动态切换
 */
public interface NotificationStrategy {

    /**
     * 获取策略名称（对应通知类型）
     */
    String getType();

    /**
     * 获取策略描述
     */
    String getDescription();

    /**
     * 发送通知
     *
     * @param notification 通知实体
     * @throws Exception 发送失败时抛出异常
     */
    void send(Notification notification) throws Exception;

    /**
     * 判断是否支持该通知类型
     */
    boolean supports(String type);

    /**
     * 获取重试策略配置
     */
    RetryConfig getRetryConfig();

    /**
     * 重试配置
     */
    class RetryConfig {
        private int maxRetries;
        private long initialBackoffMs;
        private double backoffMultiplier;
        private long maxBackoffMs;

        public RetryConfig(int maxRetries, long initialBackoffMs, double backoffMultiplier, long maxBackoffMs) {
            this.maxRetries = maxRetries;
            this.initialBackoffMs = initialBackoffMs;
            this.backoffMultiplier = backoffMultiplier;
            this.maxBackoffMs = maxBackoffMs;
        }

        public int getMaxRetries() { return maxRetries; }
        public long getInitialBackoffMs() { return initialBackoffMs; }
        public double getBackoffMultiplier() { return backoffMultiplier; }
        public long getMaxBackoffMs() { return maxBackoffMs; }
    }
}
