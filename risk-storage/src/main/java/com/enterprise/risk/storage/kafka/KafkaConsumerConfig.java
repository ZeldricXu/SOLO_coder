package com.enterprise.risk.storage.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka消费者配置
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Value("${spring.kafka.consumer.enable-auto-commit:false}")
    private boolean enableAutoCommit;

    @Value("${spring.kafka.consumer.max-poll-records:500}")
    private int maxPollRecords;

    @Value("${spring.kafka.consumer.max-poll-interval-ms:300000}")
    private int maxPollIntervalMs;

    @Value("${spring.kafka.consumer.session-timeout-ms:30000}")
    private int sessionTimeoutMs;

    @Value("${spring.kafka.consumer.concurrency:4}")
    private int concurrency;

    @Value("${spring.kafka.consumer.poll-timeout:5000}")
    private long pollTimeout;

    /**
     * JSON反序列化消费者工厂
     * 用于消费JSON格式的消息
     */
    @Bean
    public ConsumerFactory<String, Object> jsonConsumerFactory() {
        Map<String, Object> configProps = buildCommonConfig();

        ErrorHandlingDeserializer<Object> valueDeserializer = new ErrorHandlingDeserializer<>(
                new JsonDeserializer<>(Object.class, buildTrustedPackages(), true)
        );

        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        configProps.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        configProps.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, valueDeserializer);
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, buildTrustedPackages());
        configProps.put(JsonDeserializer.TYPE_MAPPINGS, buildTypeMappings());
        configProps.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, true);
        configProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.LinkedHashMap");

        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    /**
     * JSON监听器容器工厂
     * 配置手动提交ack、批量消费等
     */
    @Bean(name = "jsonKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> jsonKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(jsonConsumerFactory());
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setPollTimeout(pollTimeout);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setCommitLogLevel(org.springframework.kafka.listener.ContainerProperties.EmitEvent.ON_THREAD);
        factory.setBatchListener(true);
        return factory;
    }

    /**
     * String反序列化消费者工厂
     * 用于消费Protobuf或自定义序列化的消息
     */
    @Bean
    public ConsumerFactory<String, String> stringConsumerFactory() {
        Map<String, Object> configProps = buildCommonConfig();
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    /**
     * String监听器容器工厂
     */
    @Bean(name = "stringKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> stringKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(stringConsumerFactory());
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setPollTimeout(pollTimeout);
        factory.setBatchListener(true);
        return factory;
    }

    /**
     * 字节数组消费者工厂
     * 用于消费Protobuf二进制消息
     */
    @Bean
    public ConsumerFactory<String, byte[]> byteArrayConsumerFactory() {
        Map<String, Object> configProps = buildCommonConfig();
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.ByteArrayDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    /**
     * 字节数组监听器容器工厂（适用于Protobuf二进制消息）
     */
    @Bean(name = "byteArrayKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> byteArrayKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(byteArrayConsumerFactory());
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setPollTimeout(pollTimeout);
        factory.setBatchListener(true);
        return factory;
    }

    /**
     * 构建通用消费者配置
     */
    private Map<String, Object> buildCommonConfig() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit);
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        configProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs);
        configProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionTimeoutMs);
        configProps.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, sessionTimeoutMs / 3);
        configProps.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024 * 1024);
        configProps.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
        configProps.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 50 * 1024 * 1024);
        configProps.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
        configProps.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        return configProps;
    }

    /**
     * 构建可信包列表
     */
    private String buildTrustedPackages() {
        return "com.enterprise.risk.common.*,java.util,java.lang";
    }

    /**
     * 构建类型映射
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
