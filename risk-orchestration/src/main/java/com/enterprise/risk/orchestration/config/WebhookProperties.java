package com.enterprise.risk.orchestration.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Webhook配置属性
 * 配置Webhook的超时时间、重试策略和签名密钥
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "risk.orchestration.webhook")
public class WebhookProperties {

    /**
     * 默认连接超时时间（秒）
     */
    private Long timeoutSeconds = 10L;

    /**
     * 最大重试次数
     */
    private Integer maxRetries = 3;

    /**
     * 指数退避初始等待时间（毫秒）
     */
    private Long backoffInitialMillis = 500L;

    /**
     * 指数退避最大等待时间（毫秒）
     */
    private Long backoffMaxMillis = 5000L;

    /**
     * 默认HMAC签名密钥
     */
    private String secretKey = "";
}
