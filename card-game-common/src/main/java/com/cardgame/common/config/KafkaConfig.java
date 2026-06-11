package com.cardgame.common.config;

import lombok.Data;

@Data
public class KafkaConfig {
    private String bootstrapServers = "127.0.0.1:9092";
    private String battleLogTopic = "cardgame_battle_log";
    private String producerAcks = "1";
    private int producerRetries = 3;
    private int producerBatchSize = 16384;
    private int producerLingerMs = 5;
    private String groupId = "cardgame_replay_group";
    private int topicRetentionHours = 168;
    private long topicRetentionBytes = 10737418240L;
    private int topicSegmentBytes = 1073741824;
    private boolean topicCleanupDelete = true;
    private boolean topicCleanupCompact = false;
}
