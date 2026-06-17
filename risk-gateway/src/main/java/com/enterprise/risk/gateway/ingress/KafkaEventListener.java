package com.enterprise.risk.gateway.ingress;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.gateway.deserializer.RiskEventDeserializer;
import com.enterprise.risk.gateway.pipeline.EventIngestionPipeline;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Kafka事件消费者监听器
 * 监听events topic，支持两种消息格式的反序列化：
 * 1. JSON格式 - 通过Content-Type头识别
 * 2. Protobuf格式 - 通过Content-Type头识别或消息类型推断
 *
 * 消费模式：批量消费，手动提交offset
 */
@Slf4j
@Component
public class KafkaEventListener {

    private final EventIngestionPipeline ingestionPipeline;
    private final RiskEventDeserializer eventDeserializer;

    public KafkaEventListener(EventIngestionPipeline ingestionPipeline,
                              RiskEventDeserializer eventDeserializer) {
        this.ingestionPipeline = ingestionPipeline;
        this.eventDeserializer = eventDeserializer;
    }

    /**
     * 监听events topic - JSON格式消息
     * 单条消息消费
     *
     * @param record         Kafka消费记录
     * @param contentType    消息Content-Type头
     * @param acknowledgment 手动提交offset对象
     */
    @KafkaListener(
            topics = "${risk.gateway.kafka.events-topic:events}",
            groupId = "${risk.gateway.kafka.consumer-group:risk-gateway-group}",
            containerFactory = "kafkaListenerContainerFactory",
            autoStartup = "${risk.gateway.kafka.enabled:true}"
    )
    public void onEventMessage(
            ConsumerRecord<String, Object> record,
            @Header(value = "Content-Type", required = false) String contentType,
            Acknowledgment acknowledgment) {

        try {
            log.debug("接收Kafka事件消息, topic: {}, partition: {}, offset: {}",
                    record.topic(), record.partition(), record.offset());

            RiskEvent event = deserializeRecord(record, contentType);

            if (event != null) {
                ingestionPipeline.process(event);
            }

            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("处理Kafka事件消息失败, topic: {}, partition: {}, offset: {}",
                    record.topic(), record.partition(), record.offset(), e);
            handleConsumptionFailure(record, e);
        }
    }

    /**
     * 批量消费监听
     * 提高高吞吐量场景下的处理效率
     *
     * @param records        Kafka消费记录列表
     * @param acknowledgment 手动提交offset对象
     */
    @KafkaListener(
            topics = "${risk.gateway.kafka.events-topic:events}",
            groupId = "${risk.gateway.kafka.consumer-group-batch:risk-gateway-group-batch}",
            containerFactory = "batchKafkaListenerContainerFactory",
            batch = "true",
            autoStartup = "${risk.gateway.kafka.batch-enabled:false}"
    )
    public void onEventBatchMessage(
            List<ConsumerRecord<String, Object>> records,
            Acknowledgment acknowledgment) {

        log.debug("接收Kafka批量事件消息, 数量: {}", records.size());

        int successCount = 0;
        int failCount = 0;

        for (ConsumerRecord<String, Object> record : records) {
            try {
                RiskEvent event = deserializeRecord(record, null);
                if (event != null) {
                    ingestionPipeline.process(event);
                    successCount++;
                }
            } catch (Exception e) {
                failCount++;
                log.error("处理Kafka批量消息失败, offset: {}", record.offset(), e);
                handleConsumptionFailure(record, e);
            }
        }

        log.info("Kafka批量消费完成, 成功: {}, 失败: {}, 总数: {}", successCount, failCount, records.size());
        acknowledgment.acknowledge();
    }

    /**
     * 根据Kafka记录反序列化为RiskEvent对象
     * 自动识别JSON或Protobuf格式
     */
    private RiskEvent deserializeRecord(ConsumerRecord<String, Object> record, String contentType) {
        Object value = record.value();

        if (value == null) {
            log.warn("Kafka消息值为空, offset: {}", record.offset());
            return null;
        }

        try {
            if (value instanceof RiskEvent) {
                return (RiskEvent) value;
            }

            if (value instanceof byte[]) {
                byte[] bytes = (byte[]) value;
                if (isProbablyProtobuf(contentType, bytes)) {
                    return eventDeserializer.deserializeProtobuf(bytes);
                } else {
                    return eventDeserializer.deserializeJson(new String(bytes));
                }
            }

            if (value instanceof String) {
                return eventDeserializer.deserializeJson((String) value);
            }

            log.warn("不支持的Kafka消息类型: {}, offset: {}", value.getClass().getName(), record.offset());
            return null;

        } catch (Exception e) {
            log.error("Kafka消息反序列化失败, offset: {}", record.offset(), e);
            throw e;
        }
    }

    /**
     * 判断是否为Protobuf格式
     * 基于Content-Type头或消息字节特征判断
     */
    private boolean isProbablyProtobuf(String contentType, byte[] bytes) {
        if (contentType != null) {
            if (contentType.contains("protobuf") || contentType.contains("x-protobuf")) {
                return true;
            }
            if (contentType.contains("json")) {
                return false;
            }
        }

        if (bytes.length > 0) {
            char firstChar = (char) bytes[0];
            if (firstChar == '{' || firstChar == '[') {
                return false;
            }
        }

        return true;
    }

    /**
     * 处理消费失败的消息
     * 可扩展：写入DLQ、告警、记录失败日志等
     */
    private void handleConsumptionFailure(ConsumerRecord<String, Object> record, Exception e) {
        log.error("Kafka消息消费失败详情 - topic: {}, partition: {}, offset: {}, key: {}, error: {}",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                e.getMessage());
    }
}
