package com.enterprise.risk.gateway.dlq;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.gateway.deserializer.JsonEventConverter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 死信队列(DLQ)服务
 * 处理无效/失败事件的多种输出方式：
 * 1. 记录详细日志
 * 2. 维护计数统计（内存计数器）
 * 3. 写入Kafka DLQ Topic（异步）
 * 4. 持久化到数据库（可选，通过配置开关）
 */
@Slf4j
@Service
public class DeadLetterQueueService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final JsonEventConverter jsonEventConverter;

    /**
     * Kafka DLQ Topic名称
     */
    @Value("${risk.gateway.dlq.kafka-topic:events-dlq}")
    private String dlqTopic;

    /**
     * 是否启用Kafka DLQ写入
     */
    @Value("${risk.gateway.dlq.enable-kafka:true}")
    private boolean enableKafkaDlq;

    /**
     * 是否启用数据库持久化（可选功能）
     */
    @Value("${risk.gateway.dlq.enable-database:false}")
    private boolean enableDatabasePersistence;

    /**
     * 是否启用DLQ日志输出
     */
    @Value("${risk.gateway.dlq.enable-logging:true}")
    private boolean enableLogging;

    /**
     * 错误码计数器
     */
    private final Map<String, AtomicLong> errorCodeCounters = new ConcurrentHashMap<>();

    /**
     * 业务线DLQ计数器
     */
    private final Map<String, AtomicLong> businessLineCounters = new ConcurrentHashMap<>();

    /**
     * 全局DLQ计数器
     */
    private final AtomicLong totalDlqCount = new AtomicLong(0);

    public DeadLetterQueueService(KafkaTemplate<String, Object> kafkaTemplate,
                                  ObjectMapper objectMapper,
                                  JsonEventConverter jsonEventConverter) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.jsonEventConverter = jsonEventConverter;
    }

    /**
     * 处理校验失败的事件
     *
     * @param event          原始事件
     * @param errorCode      错误码
     * @param reason         失败原因
     * @param sourceType     来源类型
     */
    public void handleValidationFailure(RiskEvent event,
                                        String errorCode,
                                        String reason,
                                        String sourceType) {
        DeadLetterEvent dlqEvent = DeadLetterEvent.forValidationError(
                event, errorCode, reason, sourceType
        );
        process(dlqEvent);
    }

    /**
     * 处理反序列化失败的原始数据
     *
     * @param rawData        原始数据（JSON字符串或字节数组toString）
     * @param errorCode      错误码
     * @param reason         失败原因
     * @param sourceType     来源类型
     */
    public void handleDeserializationFailure(String rawData,
                                             String errorCode,
                                             String reason,
                                             String sourceType) {
        DeadLetterEvent dlqEvent = DeadLetterEvent.forDeserializationError(
                rawData, errorCode, reason, sourceType
        );
        process(dlqEvent);
    }

    /**
     * 处理通用失败事件
     *
     * @param dlqEvent DLQ事件对象
     */
    public void process(DeadLetterEvent dlqEvent) {
        if (dlqEvent == null) {
            log.warn("收到空DLQ事件，忽略处理");
            return;
        }

        updateCounters(dlqEvent);

        if (enableLogging) {
            logDlqEvent(dlqEvent);
        }

        if (enableKafkaDlq) {
            sendToKafkaDlq(dlqEvent);
        }

        if (enableDatabasePersistence) {
            persistToDatabase(dlqEvent);
        }
    }

    /**
     * 更新各类计数器
     */
    private void updateCounters(DeadLetterEvent dlqEvent) {
        totalDlqCount.incrementAndGet();

        errorCodeCounters.computeIfAbsent(
                dlqEvent.getErrorCode() != null ? dlqEvent.getErrorCode() : "UNKNOWN",
                k -> new AtomicLong(0)
        ).incrementAndGet();

        if (dlqEvent.getOriginalEvent() != null) {
            String businessLine = dlqEvent.getOriginalEvent().getBusinessLine();
            if (businessLine != null && !businessLine.isEmpty()) {
                businessLineCounters.computeIfAbsent(businessLine, k -> new AtomicLong(0))
                        .incrementAndGet();
            }
        }
    }

    /**
     * 记录DLQ事件日志
     */
    private void logDlqEvent(DeadLetterEvent dlqEvent) {
        StringBuilder logMsg = new StringBuilder();
        logMsg.append("DLQ事件记录 - ");
        logMsg.append("dlqId: ").append(dlqEvent.getDlqId());
        logMsg.append(", errorCode: ").append(dlqEvent.getErrorCode());
        logMsg.append(", reason: ").append(dlqEvent.getReason());
        logMsg.append(", sourceType: ").append(dlqEvent.getSourceType());
        logMsg.append(", retryCount: ").append(dlqEvent.getRetryCount());

        if (dlqEvent.getOriginalEvent() != null) {
            RiskEvent event = dlqEvent.getOriginalEvent();
            logMsg.append(", eventId: ").append(event.getEventId());
            logMsg.append(", eventType: ").append(event.getEventType());
            logMsg.append(", businessLine: ").append(event.getBusinessLine());
            logMsg.append(", entityId: ").append(event.getEntityId());
        }

        log.warn(logMsg.toString());
    }

    /**
     * 异步发送到Kafka DLQ Topic
     */
    @Async
    public void sendToKafkaDlq(DeadLetterEvent dlqEvent) {
        try {
            Map<String, Object> dlqMessage = buildDlqMessage(dlqEvent);

            String key = dlqEvent.getOriginalEvent() != null
                    ? dlqEvent.getOriginalEvent().getEventId()
                    : dlqEvent.getDlqId();

            kafkaTemplate.send(dlqTopic, key, dlqMessage)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("DLQ消息发送到Kafka失败, dlqId: {}", dlqEvent.getDlqId(), ex);
                        } else {
                            log.debug("DLQ消息发送到Kafka成功, dlqId: {}, partition: {}, offset: {}",
                                    dlqEvent.getDlqId(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });

        } catch (Exception e) {
            log.error("构造DLQ Kafka消息失败, dlqId: {}", dlqEvent.getDlqId(), e);
        }
    }

    /**
     * 构造DLQ消息Map（用于Kafka发送）
     */
    private Map<String, Object> buildDlqMessage(DeadLetterEvent dlqEvent) {
        try {
            String eventJson = dlqEvent.getOriginalEventJson();
            if (eventJson == null && dlqEvent.getOriginalEvent() != null) {
                eventJson = jsonEventConverter.toJson(dlqEvent.getOriginalEvent());
            }

            return Map.of(
                    "dlq_id", dlqEvent.getDlqId() != null ? dlqEvent.getDlqId() : "",
                    "error_code", dlqEvent.getErrorCode() != null ? dlqEvent.getErrorCode() : "",
                    "reason", dlqEvent.getReason() != null ? dlqEvent.getReason() : "",
                    "failed_at", dlqEvent.getFailedAt() != null ? dlqEvent.getFailedAt() : Instant.now().toEpochMilli(),
                    "retry_count", dlqEvent.getRetryCount() != null ? dlqEvent.getRetryCount() : 0,
                    "source_type", dlqEvent.getSourceType() != null ? dlqEvent.getSourceType() : "",
                    "source_identifier", dlqEvent.getSourceIdentifier() != null ? dlqEvent.getSourceIdentifier() : "",
                    "original_event", eventJson != null ? eventJson : ""
            );
        } catch (Exception e) {
            log.error("构建DLQ消息Map失败", e);
            return Map.of(
                    "dlq_id", dlqEvent.getDlqId() != null ? dlqEvent.getDlqId() : "",
                    "error", "BUILD_DLQ_MESSAGE_FAILED: " + e.getMessage()
            );
        }
    }

    /**
     * 持久化到数据库（可选功能）
     * 预留接口，可通过risk-storage模块扩展
     */
    @Async
    public void persistToDatabase(DeadLetterEvent dlqEvent) {
        try {
            log.debug("DLQ数据库持久化（预留功能）, dlqId: {}", dlqEvent.getDlqId());

        } catch (Exception e) {
            log.error("DLQ数据库持久化失败, dlqId: {}", dlqEvent.getDlqId(), e);
        }
    }

    /**
     * 获取全局DLQ计数
     */
    public long getTotalDlqCount() {
        return totalDlqCount.get();
    }

    /**
     * 获取指定错误码的DLQ计数
     */
    public long getErrorCodeCount(String errorCode) {
        AtomicLong counter = errorCodeCounters.get(errorCode);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 获取指定业务线的DLQ计数
     */
    public long getBusinessLineCount(String businessLine) {
        AtomicLong counter = businessLineCounters.get(businessLine);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 获取所有错误码计数统计
     */
    public Map<String, AtomicLong> getErrorCodeCounters() {
        return errorCodeCounters;
    }

    /**
     * 获取所有业务线计数统计
     */
    public Map<String, AtomicLong> getBusinessLineCounters() {
        return businessLineCounters;
    }

    /**
     * 重置所有计数器
     */
    public void resetCounters() {
        totalDlqCount.set(0);
        errorCodeCounters.clear();
        businessLineCounters.clear();
        log.info("DLQ计数器已重置");
    }

    @PreDestroy
    public void shutdown() {
        log.info("DLQ服务关闭 - 总计处理DLQ事件: {}", totalDlqCount.get());
        if (!errorCodeCounters.isEmpty()) {
            log.info("DLQ错误码统计: {}", errorCodeCounters);
        }
        if (!businessLineCounters.isEmpty()) {
            log.info("DLQ业务线统计: {}", businessLineCounters);
        }
    }
}
