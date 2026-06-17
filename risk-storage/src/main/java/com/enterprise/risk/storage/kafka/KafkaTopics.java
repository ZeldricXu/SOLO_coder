package com.enterprise.risk.storage.kafka;

/**
 * Kafka Topic 定义常量类
 */
public final class KafkaTopics {

    private KafkaTopics() {}

    /**
     * 风险事件主题 - 接收原始风险事件
     */
    public static final String EVENTS = "risk.events";

    /**
     * 死信队列主题 - 处理失败的事件
     */
    public static final String DLQ = "risk.events.dlq";

    /**
     * 告警主题 - 生成的告警信息
     */
    public static final String ALERTS = "risk.alerts";

    /**
     * 动作执行主题 - 需要执行的风控动作
     */
    public static final String ACTIONS = "risk.actions";

    /**
     * 规则命中日志主题
     */
    public static final String RULE_HIT_LOGS = "risk.rule_hit_logs";

    /**
     * 模型推理日志主题
     */
    public static final String MODEL_INFERENCE_LOGS = "risk.model_inference_logs";

    /**
     * 指标监控主题
     */
    public static final String METRICS = "risk.metrics";

    /**
     * 获取主题分区数量（可通过配置覆盖）
     */
    public static final class Partitions {
        public static final int EVENTS = 12;
        public static final int DLQ = 3;
        public static final int ALERTS = 6;
        public static final int ACTIONS = 6;
        public static final int RULE_HIT_LOGS = 6;
        public static final int MODEL_INFERENCE_LOGS = 3;
        public static final int METRICS = 2;

        private Partitions() {}
    }

    /**
     * 获取主题副本因子
     */
    public static final class ReplicationFactor {
        public static final short EVENTS = 2;
        public static final short DLQ = 2;
        public static final short ALERTS = 2;
        public static final short ACTIONS = 2;
        public static final short RULE_HIT_LOGS = 2;
        public static final short MODEL_INFERENCE_LOGS = 2;
        public static final short METRICS = 1;

        private ReplicationFactor() {}
    }

    /**
     * 消费者组定义
     */
    public static final class ConsumerGroups {
        public static final String RISK_ENGINE = "risk-engine-group";
        public static final String ALERT_PROCESSOR = "alert-processor-group";
        public static final String ACTION_EXECUTOR = "action-executor-group";
        public static final String EVENT_STORAGE = "event-storage-group";
        public static final String METRICS_COLLECTOR = "metrics-collector-group";

        private ConsumerGroups() {}
    }
}
