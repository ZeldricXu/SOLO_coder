package com.enterprise.risk.storage.kafka;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.common.event.RiskEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * 事件发布器
 * 统一封装Kafka消息发布逻辑，支持JSON和Protobuf两种格式
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublisher {

    private final KafkaTemplate<String, Object> jsonKafkaTemplate;
    private final KafkaTemplate<String, String> stringKafkaTemplate;
    private final KafkaTemplate<String, byte[]> byteArrayKafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发布风险事件到events主题（JSON格式）
     *
     * @param event 风险事件
     * @return 发送结果Future
     */
    public CompletableFuture<SendResult<String, Object>> publishEvent(RiskEvent event) {
        String key = buildEventKey(event);
        return sendJson(KafkaTopics.EVENTS, key, event,
                (result, ex) -> handleSendResult("EVENT", event.getEventId(), result, ex));
    }

    /**
     * 发布告警到alerts主题（JSON格式）
     *
     * @param alert 告警事件
     * @return 发送结果Future
     */
    public CompletableFuture<SendResult<String, Object>> publishAlert(AlertEvent alert) {
        String key = alert.getFingerprint() != null ? alert.getFingerprint() : alert.getAlertId();
        return sendJson(KafkaTopics.ALERTS, key, alert,
                (result, ex) -> handleSendResult("ALERT", alert.getAlertId(), result, ex));
    }

    /**
     * 发布动作到actions主题（JSON格式）
     *
     * @param actionId   动作ID
     * @param partitionKey 分区键（如entityId）
     * @param payload    动作负载
     * @return 发送结果Future
     */
    public CompletableFuture<SendResult<String, Object>> publishAction(
            String actionId, String partitionKey, Object payload) {
        return sendJson(KafkaTopics.ACTIONS, partitionKey, payload,
                (result, ex) -> handleSendResult("ACTION", actionId, result, ex));
    }

    /**
     * 发布死信消息到dlq主题
     *
     * @param originalTopic 原始主题
     * @param key           原始消息键
     * @param value         原始消息内容
     * @param errorMessage  错误信息
     * @return 发送结果Future
     */
    public CompletableFuture<SendResult<String, Object>> publishToDlq(
            String originalTopic, String key, Object value, String errorMessage) {
        java.util.Map<String, Object> dlqMessage = new java.util.HashMap<>();
        dlqMessage.put("original_topic", originalTopic);
        dlqMessage.put("original_key", key);
        dlqMessage.put("original_value", value);
        dlqMessage.put("error_message", errorMessage);
        dlqMessage.put("dlq_timestamp", System.currentTimeMillis());

        String dlqKey = originalTopic + ":" + (key != null ? key : "null");
        return sendJson(KafkaTopics.DLQ, dlqKey, dlqMessage,
                (result, ex) -> handleSendResult("DLQ", dlqKey, result, ex));
    }

    /**
     * 发布Protobuf格式消息（使用Base64编码的String方式）
     *
     * @param topic   主题
     * @param key     消息键
     * @param message Protobuf消息
     * @return 发送结果Future
     */
    public CompletableFuture<SendResult<String, String>> publishProtobufAsString(
            String topic, String key, Message message) {
        String payload = Base64.getEncoder().encodeToString(message.toByteArray());
        CompletableFuture<SendResult<String, String>> future =
                stringKafkaTemplate.send(topic, key, payload);
        future.whenComplete((result, ex) ->
                handleSendResult("PROTOBUF_STR", key, result != null ? result.getRecordMetadata().topic() : topic, ex));
        return future;
    }

    /**
     * 发布Protobuf格式消息（使用原始byte[]方式，推荐）
     *
     * @param topic   主题
     * @param key     消息键
     * @param message Protobuf消息
     * @return 发送结果Future
     */
    public CompletableFuture<SendResult<String, byte[]>> publishProtobuf(
            String topic, String key, Message message) {
        byte[] payload = message.toByteArray();
        CompletableFuture<SendResult<String, byte[]>> future =
                byteArrayKafkaTemplate.send(topic, key, payload);
        future.whenComplete((result, ex) ->
                handleSendResult("PROTOBUF", key, result != null ? result.getRecordMetadata().topic() : topic, ex));
        return future;
    }

    /**
     * 通用JSON消息发送
     *
     * @param topic    主题
     * @param key      消息键
     * @param value    消息值
     * @param callback 回调函数
     * @return 发送结果Future
     */
    private CompletableFuture<SendResult<String, Object>> sendJson(
            String topic, String key, Object value,
            BiConsumer<SendResult<String, Object>, Throwable> callback) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("发送Kafka消息: topic={}, key={}, value={}",
                        topic, key, objectMapper.writeValueAsString(value));
            }
            CompletableFuture<SendResult<String, Object>> future =
                    jsonKafkaTemplate.send(topic, key, value);
            if (callback != null) {
                future.whenComplete(callback);
            }
            return future;
        } catch (Exception e) {
            log.error("构造Kafka消息失败: topic={}, key={}", topic, key, e);
            CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    /**
     * 通用发送结果处理
     */
    private void handleSendResult(String type, String key,
                                  SendResult<String, Object> result, Throwable ex) {
        if (ex != null) {
            log.error("发送Kafka消息失败: type={}, key={}", type, key, ex);
        } else if (result != null) {
            if (log.isDebugEnabled()) {
                log.debug("Kafka消息发送成功: type={}, key={}, partition={}, offset={}",
                        type, key,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        }
    }

    /**
     * 处理发送结果（简化版）
     */
    private void handleSendResult(String type, String key, String topic, Throwable ex) {
        if (ex != null) {
            log.error("发送Kafka消息失败: type={}, topic={}, key={}", type, topic, key, ex);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Kafka消息发送成功: type={}, topic={}, key={}", type, topic, key);
            }
        }
    }

    /**
     * 构建风险事件的分区键
     * 优先使用entityKey保证同一实体的消息落到同一分区
     */
    private String buildEventKey(RiskEvent event) {
        if (event.getEntityType() != null && event.getEntityId() != null) {
            return event.getEntityType() + ":" + event.getEntityId();
        }
        if (event.getUserId() != null) {
            return "user:" + event.getUserId();
        }
        if (event.getIp() != null) {
            return "ip:" + event.getIp();
        }
        return event.getEventId();
    }

    /**
     * 同步发送JSON消息（等待确认）
     *
     * @param topic 主题
     * @param key   消息键
     * @param value 消息值
     * @return 发送结果
     */
    public SendResult<String, Object> syncSendJson(String topic, String key, Object value) {
        try {
            return sendJson(topic, key, value, null).get();
        } catch (Exception e) {
            log.error("同步发送Kafka消息失败: topic={}, key={}", topic, key, e);
            throw new RuntimeException("Kafka同步发送失败", e);
        }
    }

    /**
     * 同步发送Protobuf消息
     *
     * @param topic   主题
     * @param key     消息键
     * @param message Protobuf消息
     * @return 发送结果
     */
    public SendResult<String, byte[]> syncSendProtobuf(String topic, String key, Message message) {
        try {
            return publishProtobuf(topic, key, message).get();
        } catch (Exception e) {
            log.error("同步发送Protobuf消息失败: topic={}, key={}", topic, key, e);
            throw new RuntimeException("Kafka同步发送失败", e);
        }
    }

    /**
     * 刷新生产者缓冲区，确保所有待发送消息已发送
     */
    public void flush() {
        jsonKafkaTemplate.flush();
        stringKafkaTemplate.flush();
        byteArrayKafkaTemplate.flush();
    }
}
