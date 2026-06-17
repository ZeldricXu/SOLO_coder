package com.enterprise.risk.common.orchestration;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 动作定义实体
 * 定义风控系统中可执行的动作，包括动作类型、参数、Webhook配置等
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionDefinition implements Serializable {

    @JsonProperty("action_id")
    private String actionId;

    @JsonProperty("action_name")
    private String actionName;

    @JsonProperty("action_type")
    private ActionType actionType;

    @JsonProperty("description")
    private String description;

    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = true;

    @JsonProperty("priority")
    @Builder.Default
    private Integer priority = 100;

    @JsonProperty("business_line")
    private String businessLine;

    @JsonProperty("event_types")
    private List<String> eventTypes;

    @JsonProperty("params")
    @Builder.Default
    private Map<String, Object> params = new HashMap<>();

    @JsonProperty("webhook_config")
    private WebhookConfig webhookConfig;

    @JsonProperty("retry_config")
    private RetryConfig retryConfig;

    @JsonProperty("rate_limit_config")
    private RateLimitConfig rateLimitConfig;

    @JsonProperty("timeout_ms")
    @Builder.Default
    private Long timeoutMs = 5000L;

    @JsonProperty("async")
    @Builder.Default
    private Boolean async = true;

    @JsonProperty("condition_expression")
    private String conditionExpression;

    @JsonProperty("created_at")
    @Builder.Default
    private Long createdAt = Instant.now().toEpochMilli();

    @JsonProperty("updated_at")
    @Builder.Default
    private Long updatedAt = Instant.now().toEpochMilli();

    @JsonProperty("created_by")
    private String createdBy;

    @JsonProperty("version")
    @Builder.Default
    private Integer version = 1;

    /**
     * Webhook配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WebhookConfig implements Serializable {

        @JsonProperty("url")
        private String url;

        @JsonProperty("method")
        @Builder.Default
        private String method = "POST";

        @JsonProperty("headers")
        @Builder.Default
        private Map<String, String> headers = new HashMap<>();

        @JsonProperty("query_params")
        @Builder.Default
        private Map<String, String> queryParams = new HashMap<>();

        @JsonProperty("body_template")
        private String bodyTemplate;

        @JsonProperty("content_type")
        @Builder.Default
        private String contentType = "application/json";

        @JsonProperty("auth_type")
        private AuthType authType;

        @JsonProperty("auth_token")
        private String authToken;

        @JsonProperty("auth_username")
        private String authUsername;

        @JsonProperty("auth_password")
        private String authPassword;

        @JsonProperty("secret_key")
        private String secretKey;

        @JsonProperty("sign_header")
        @Builder.Default
        private String signHeader = "X-Signature";

        @JsonProperty("sign_algorithm")
        @Builder.Default
        private String signAlgorithm = "HmacSHA256";

        @JsonProperty("success_status_codes")
        @Builder.Default
        private List<Integer> successStatusCodes = List.of(200, 201, 202, 204);

        @JsonProperty("response_path")
        private String responsePath;

        /**
         * Webhook认证类型
         */
        public enum AuthType {
            NONE,
            BEARER_TOKEN,
            BASIC_AUTH,
            API_KEY,
            CUSTOM_SIGN
        }
    }

    /**
     * 重试配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetryConfig implements Serializable {

        @JsonProperty("max_retries")
        @Builder.Default
        private Integer maxRetries = 3;

        @JsonProperty("initial_delay_ms")
        @Builder.Default
        private Long initialDelayMs = 1000L;

        @JsonProperty("max_delay_ms")
        @Builder.Default
        private Long maxDelayMs = 60000L;

        @JsonProperty("multiplier")
        @Builder.Default
        private Double multiplier = 2.0;

        @JsonProperty("retry_strategy")
        @Builder.Default
        private RetryStrategy retryStrategy = RetryStrategy.EXPONENTIAL_BACKOFF;

        @JsonProperty("retry_on_status_codes")
        @Builder.Default
        private List<Integer> retryOnStatusCodes = List.of(408, 429, 500, 502, 503, 504);

        @JsonProperty("retry_on_exceptions")
        @Builder.Default
        private List<String> retryOnExceptions = List.of(
                "java.net.SocketTimeoutException",
                "java.net.ConnectException",
                "java.io.IOException"
        );

        /**
         * 重试策略
         */
        public enum RetryStrategy {
            FIXED,
            LINEAR,
            EXPONENTIAL_BACKOFF,
            RANDOM
        }
    }

    /**
     * 限流配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimitConfig implements Serializable {

        @JsonProperty("enabled")
        @Builder.Default
        private Boolean enabled = false;

        @JsonProperty("max_requests")
        @Builder.Default
        private Integer maxRequests = 100;

        @JsonProperty("window_size_ms")
        @Builder.Default
        private Long windowSizeMs = 60000L;

        @JsonProperty("limit_by")
        @Builder.Default
        private LimitBy limitBy = LimitBy.GLOBAL;

        @JsonProperty("group_key")
        private String groupKey;

        @JsonProperty("fallback_action")
        private String fallbackAction;

        /**
         * 限流维度
         */
        public enum LimitBy {
            GLOBAL,
            BY_ENTITY,
            BY_RULE,
            BY_BUSINESS_LINE,
            BY_CUSTOM_KEY
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean isAsync() {
        return Boolean.TRUE.equals(async);
    }

    public boolean hasWebhookConfig() {
        return webhookConfig != null && webhookConfig.getUrl() != null;
    }

    public boolean hasRetryConfig() {
        return retryConfig != null && retryConfig.getMaxRetries() != null && retryConfig.getMaxRetries() > 0;
    }

    public boolean isRateLimitEnabled() {
        return rateLimitConfig != null && Boolean.TRUE.equals(rateLimitConfig.getEnabled());
    }

    /**
     * 获取指定参数值
     */
    @SuppressWarnings("unchecked")
    public <T> T getParam(String key) {
        if (params == null) {
            return null;
        }
        return (T) params.get(key);
    }

    /**
     * 获取指定参数值，带默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getParamOrDefault(String key, T defaultValue) {
        if (params == null) {
            return defaultValue;
        }
        return (T) params.getOrDefault(key, defaultValue);
    }

    /**
     * 设置参数
     */
    public void setParam(String key, Object value) {
        if (this.params == null) {
            this.params = new HashMap<>();
        }
        this.params.put(key, value);
    }
}
