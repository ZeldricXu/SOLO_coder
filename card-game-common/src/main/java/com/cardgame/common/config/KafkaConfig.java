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
}
