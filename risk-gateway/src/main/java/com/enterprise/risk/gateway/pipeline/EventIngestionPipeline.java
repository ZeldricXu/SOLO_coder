package com.enterprise.risk.gateway.pipeline;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.gateway.dlq.DeadLetterQueueService;
import com.enterprise.risk.gateway.ratelimit.RateLimitService;
import com.enterprise.risk.gateway.validator.EventValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件接入流水线
 * 核心处理流程：限流 -> 校验 -> DLQ分流 -> 发布到Kafka事件总线 -> 返回
 *
 * 设计特点：
 * 1. 纯内存操作，低延迟
 * 2. Kafka异步发布，不阻塞主流程
 * 3. 持久化异步执行，通过异步线程池处理
 * 4. 完善的指标统计和监控
 */
@Slf4j
@Service
public class EventIngestionPipeline {

    private final RateLimitService rateLimitService;
    private final EventValidator eventValidator;
    private final DeadLetterQueueService dlqService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Kafka事件总线Topic名称
     */
    @Value("${risk.gateway.kafka.events-topic:events}")
    private String eventsTopic;

    /**
     * 是否启用Kafka发布
     */
    @Value("${risk.gateway.pipeline.enable-kafka-publish:true}")
    private boolean enableKafkaPublish;

    /**
     * 来源类型标识
     */
    private static final String SOURCE_TYPE_HTTP = "HTTP";
    private static final String SOURCE_TYPE_KAFKA = "KAFKA";

    /**
     * 处理统计指标
     */
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalAccepted = new AtomicLong(0);
    private final AtomicLong totalRejected = new AtomicLong(0);
    private final AtomicLong totalDlq = new AtomicLong(0);

    /**
     * 业务线处理统计
     */
    private final Map<String, AtomicLong> businessLineCounters = new ConcurrentHashMap<>();

    /**
     * 事件类型处理统计
     */
    private final Map<String, AtomicLong> eventTypeCounters = new ConcurrentHashMap<>();

    public EventIngestionPipeline(RateLimitService rateLimitService,
                                  EventValidator eventValidator,
                                  DeadLetterQueueService dlqService,
                                  KafkaTemplate<String, Object> kafkaTemplate,
                                  ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.eventValidator = eventValidator;
        this.dlqService = dlqService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理单个事件（同步入口）
     * 执行流水线的各个阶段，返回事件ID表示已接收
     *
     * @param event 待处理的事件
     * @return 事件ID
     */
    public String process(RiskEvent event) {
        return process(event, SOURCE_TYPE_HTTP);
    }

    /**
     * 处理单个事件（指定来源类型）
     *
     * @param event      待处理的事件
     * @param sourceType 来源类型（HTTP/KAFKA）
     * @return 事件ID
     */
    public String process(RiskEvent event, String sourceType) {
        long startTime = System.nanoTime();
        totalProcessed.incrementAndGet();

        try {
            rateLimitService.checkRateLimit(event);

            EventValidator.EventValidationResult validationResult = eventValidator.validate(event);
            if (!validationResult.isValid()) {
                totalDlq.incrementAndGet();
                totalRejected.incrementAndGet();
                dlqService.handleValidationFailure(
                        event,
                        "VALIDATION_ERROR",
                        validationResult.getErrorSummary(),
                        sourceType
                );
                return event.getEventId();
            }

            enrichEvent(event, sourceType);

            if (enableKafkaPublish) {
                publishToKafka(event);
            }

            updateSuccessCounters(event);
            totalAccepted.incrementAndGet();

            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.debug("事件处理完成, eventId: {}, 耗时: {} ms, eventType: {}, businessLine: {}",
                    event.getEventId(), durationMs, event.getEventType(), event.getBusinessLine());

            return event.getEventId();

        } catch (com.enterprise.risk.common.exception.RateLimitExceededException e) {
            totalDlq.incrementAndGet();
            totalRejected.incrementAndGet();
            dlqService.handleValidationFailure(event, e.getErrorCode(), e.getMessage(), sourceType);
            throw e;

        } catch (Exception e) {
            totalDlq.incrementAndGet();
            totalRejected.incrementAndGet();
            log.error("事件处理异常, eventId: {}, error: {}", event.getEventId(), e.getMessage(), e);
            dlqService.handleValidationFailure(event, "PIPELINE_ERROR", e.getMessage(), sourceType);
            return event.getEventId();
        }
    }

    /**
     * 批量处理事件
     *
     * @param events     事件列表
     * @param sourceType 来源类型
     * @return 成功接收的事件ID列表
     */
    public java.util.List<String> processBatch(java.util.List<RiskEvent> events, String sourceType) {
        java.util.List<String> eventIds = new java.util.ArrayList<>(events.size());
        for (RiskEvent event : events) {
            try {
                String eventId = process(event, sourceType);
                eventIds.add(eventId);
            } catch (Exception e) {
                log.warn("批量处理单个事件失败, eventId: {}", event.getEventId(), e);
            }
        }
        return eventIds;
    }

    /**
     * 事件补充：填充默认值、设置接收时间等
     */
    private void enrichEvent(RiskEvent event, String sourceType) {
        if (event.getTimestamp() == null) {
            event.setTimestamp(Instant.now().toEpochMilli());
        }

        String existingSource = event.getSource();
        if (existingSource == null || existingSource.isEmpty()) {
            event.setSource("GATEWAY_" + sourceType);
        }

        if (event.getAttributes() == null) {
            event.setAttributes(new java.util.HashMap<>());
        }

        event.getAttributes().putIfAbsent("_received_at", Instant.now().toEpochMilli());
        event.getAttributes().putIfAbsent("_ingest_source", sourceType.toLowerCase());
    }

    /**
     * 异步发布事件到Kafka事件总线
     * 不阻塞主流程，纯内存操作返回后异步持久化
     */
    @Async
    public CompletableFuture<SendResult<String, Object>> publishToKafka(RiskEvent event) {
        try {
            String key = event.getBusinessLine() + ":" + event.getEntityId();

            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(eventsTopic, key, event);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka事件发布失败, eventId: {}, topic: {}",
                            event.getEventId(), eventsTopic, ex);
                    handleKafkaPublishFailure(event, ex);
                } else {
                    log.debug("Kafka事件发布成功, eventId: {}, partition: {}, offset: {}",
                            event.getEventId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });

            return future;

        } catch (Exception e) {
            log.error("Kafka事件发布异常, eventId: {}", event.getEventId(), e);
            handleKafkaPublishFailure(event, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 处理Kafka发布失败
     * 记录DLQ以便后续重试
     */
    private void handleKafkaPublishFailure(RiskEvent event, Throwable ex) {
        try {
            dlqService.handleValidationFailure(
                    event,
                    "KAFKA_PUBLISH_ERROR",
                    "Kafka发布失败: " + ex.getMessage(),
                    "GATEWAY"
            );
        } catch (Exception dlqEx) {
            log.error("写入DLQ失败, eventId: {}", event.getEventId(), dlqEx);
        }
    }

    /**
     * 更新成功处理的统计计数器
     */
    private void updateSuccessCounters(RiskEvent event) {
        if (event.getBusinessLine() != null) {
            businessLineCounters.computeIfAbsent(event.getBusinessLine(), k -> new AtomicLong(0))
                    .incrementAndGet();
        }

        if (event.getEventType() != null) {
            eventTypeCounters.computeIfAbsent(event.getEventType(), k -> new AtomicLong(0))
                    .incrementAndGet();
        }
    }

    /**
     * 获取流水线统计指标
     */
    public PipelineMetrics getMetrics() {
        return PipelineMetrics.builder()
                .totalProcessed(totalProcessed.get())
                .totalAccepted(totalAccepted.get())
                .totalRejected(totalRejected.get())
                .totalDlq(totalDlq.get())
                .acceptRate(totalProcessed.get() > 0
                        ? (double) totalAccepted.get() / totalProcessed.get()
                        : 0.0)
                .businessLineCounters(businessLineCounters)
                .eventTypeCounters(eventTypeCounters)
                .build();
    }

    /**
     * 重置所有统计指标
     */
    public void resetMetrics() {
        totalProcessed.set(0);
        totalAccepted.set(0);
        totalRejected.set(0);
        totalDlq.set(0);
        businessLineCounters.clear();
        eventTypeCounters.clear();
        log.info("接入流水线统计指标已重置");
    }

    @PreDestroy
    public void shutdown() {
        log.info("事件接入流水线关闭 - 统计: {}", getMetrics());
    }

    /**
     * 流水线统计指标
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PipelineMetrics implements java.io.Serializable {
        private long totalProcessed;
        private long totalAccepted;
        private long totalRejected;
        private long totalDlq;
        private double acceptRate;
        private Map<String, AtomicLong> businessLineCounters;
        private Map<String, AtomicLong> eventTypeCounters;
    }
}
