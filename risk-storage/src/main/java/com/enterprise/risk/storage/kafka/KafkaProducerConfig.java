package com.enterprise.risk.storage.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka生产者配置
 * 支持JSON和Protobuf两种序列化方式
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.producer.acks:all}")
    private String acks;

    @Value("${spring.kafka.producer.retries:3}")
    private int retries;

    @Value("${spring.kafka.producer.batch-size:16384}")
    private int batchSize;

    @Value("${spring.kafka.producer.buffer-memory:33554432}")
    private long bufferMemory;

    @Value("${spring.kafka.producer.linger-ms:10}")
    private long lingerMs;

    @Value("${spring.kafka.producer.compression-type:snappy}")
    private String compressionType;

    @Value("${spring.kafka.producer.max-in-flight:5}")
    private int maxInFlight;

    /**
     * JSON序列化生产者工厂
     * 用于普通Java对象的Kafka消息发送
     */
    @Bean
    public ProducerFactory<String, Object> jsonProducerFactory() {
        Map<String, Object> configProps = buildCommonConfig();
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(JsonSerializer.TYPE_MAPPINGS, buildTypeMappings());
        configProps.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, true);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * JSON序列化KafkaTemplate
     */
    @Bean(name = "jsonKafkaTemplate")
    public KafkaTemplate<String, Object> jsonKafkaTemplate() {
        return new KafkaTemplate<>(jsonProducerFactory());
    }

    /**
     * String序列化生产者工厂
     * 用于Protobuf或自定义序列化的消息发送
     * 发送端需自行将Protobuf对象序列化为byte[]或String
     */
    @Bean
    public ProducerFactory<String, String> stringProducerFactory() {
        Map<String, Object> configProps = buildCommonConfig();
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * String序列化KafkaTemplate
     * 适用于Protobuf消息（发送前序列化为Base64或byte[]转String）
     */
    @Bean(name = "stringKafkaTemplate")
    public KafkaTemplate<String, String> stringKafkaTemplate() {
        return new KafkaTemplate<>(stringProducerFactory());
    }

    /**
     * 字节数组生产者工厂
     * 用于发送原始byte[]数据（如Protobuf二进制序列化）
     */
    @Bean
    public ProducerFactory<String, byte[]> byteArrayProducerFactory() {
        Map<String, Object> configProps = buildCommonConfig();
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.ByteArraySerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * 字节数组KafkaTemplate（适用于Protobuf二进制消息）
     */
    @Bean(name = "byteArrayKafkaTemplate")
    public KafkaTemplate<String, byte[]> byteArrayKafkaTemplate() {
        return new KafkaTemplate<>(byteArrayProducerFactory());
    }

    /**
     * 构建通用生产者配置
     */
    private Map<String, Object> buildCommonConfig() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.ACKS_CONFIG, acks);
        configProps.put(ProducerConfig.RETRIES_CONFIG, retries);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compressionType);
        configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, maxInFlight);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
        return configProps;
    }

    /**
     * 构建JSON类型映射
     * 格式：别名:全限定类名
     */
    private String buildTypeMappings() {
        return String.join(",",
                "riskEvent:com.enterprise.risk.common.event.RiskEvent",
                "alertEvent:com.enterprise.risk.common.alert.AlertEvent",
                "ruleDefinition:com.enterprise.risk.common.rule.RuleDefinition",
                "ruleEvaluationResult:com.enterprise.risk.common.rule.RuleEvaluationResult",
                "modelConfig:com.enterprise.risk.common.model.ModelConfig"
        );
    }
}
