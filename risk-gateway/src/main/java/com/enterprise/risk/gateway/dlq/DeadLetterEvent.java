package com.enterprise.risk.gateway.dlq;

import com.enterprise.risk.common.event.RiskEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 死信队列(DLQ)事件包装类
 * 封装原始事件及失败相关信息，用于追踪和重试
 *
 * 包含信息：
 * - 原始事件数据
 * - 失败原因
 * - 错误码
 * - 失败时间
 * - 重试次数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterEvent implements Serializable {

    /**
     * DLQ记录唯一ID
     */
    @Builder.Default
    private String dlqId = java.util.UUID.randomUUID().toString();

    /**
     * 原始风险事件对象
     */
    private RiskEvent originalEvent;

    /**
     * 原始事件JSON字符串（用于持久化存储）
     */
    private String originalEventJson;

    /**
     * 失败原因描述
     */
    private String reason;

    /**
     * 错误码
     * 如：VALIDATION_ERROR, RATE_LIMIT_EXCEEDED, DESERIALIZATION_ERROR等
     */
    private String errorCode;

    /**
     * 失败时间戳（毫秒）
     */
    @Builder.Default
    private Long failedAt = Instant.now().toEpochMilli();

    /**
     * 已重试次数
     */
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * 来源类型：HTTP / KAFKA
     */
    private String sourceType;

    /**
     * 来源标识（如HTTP请求IP、Kafka topic+partition+offset）
     */
    private String sourceIdentifier;

    /**
     * 附加信息（用于调试）
     */
    private String additionalInfo;

    /**
     * 创建DLQ事件的便捷工厂方法 - 校验失败场景
     */
    public static DeadLetterEvent forValidationError(RiskEvent event,
                                                     String errorCode,
                                                     String reason,
                                                     String sourceType) {
        return DeadLetterEvent.builder()
                .originalEvent(event)
                .errorCode(errorCode)
                .reason(reason)
                .sourceType(sourceType)
                .retryCount(0)
                .build();
    }

    /**
     * 创建DLQ事件的便捷工厂方法 - 反序列化失败场景
     */
    public static DeadLetterEvent forDeserializationError(String rawData,
                                                          String errorCode,
                                                          String reason,
                                                          String sourceType) {
        return DeadLetterEvent.builder()
                .originalEventJson(rawData)
                .errorCode(errorCode)
                .reason(reason)
                .sourceType(sourceType)
                .retryCount(0)
                .build();
    }

    /**
     * 标记为已重试，递增重试计数
     */
    public DeadLetterEvent incrementRetry() {
        this.retryCount = this.retryCount == null ? 1 : this.retryCount + 1;
        this.failedAt = Instant.now().toEpochMilli();
        return this;
    }
}
