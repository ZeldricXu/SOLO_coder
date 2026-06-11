package com.cardgame.replay.kafka;

import com.cardgame.common.config.KafkaConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class KafkaTopicManager {

    @Autowired
    private KafkaConfig kafkaConfig;

    private AdminClient adminClient;

    @PostConstruct
    public void init() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.getBootstrapServers());
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "30000");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "30000");

        adminClient = AdminClient.create(props);
        log.info("Kafka admin client initialized");

        try {
            createOrUpdateTopic();
        } catch (Exception e) {
            log.error("Failed to create/update Kafka topic: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (adminClient != null) {
            adminClient.close();
            log.info("Kafka admin client closed");
        }
    }

    public void createOrUpdateTopic() throws ExecutionException, InterruptedException, TimeoutException {
        String topicName = kafkaConfig.getBattleLogTopic();
        int numPartitions = 6;
        short replicationFactor = 1;

        Map<String, String> topicConfigs = new HashMap<>();

        topicConfigs.put(TopicConfig.RETENTION_MS_CONFIG,
                String.valueOf(TimeUnit.HOURS.toMillis(kafkaConfig.getTopicRetentionHours())));

        topicConfigs.put(TopicConfig.RETENTION_BYTES_CONFIG,
                String.valueOf(kafkaConfig.getTopicRetentionBytes()));

        topicConfigs.put(TopicConfig.SEGMENT_BYTES_CONFIG,
                String.valueOf(kafkaConfig.getTopicSegmentBytes()));

        StringBuilder cleanupPolicy = new StringBuilder();
        if (kafkaConfig.isTopicCleanupDelete()) {
            cleanupPolicy.append("delete");
        }
        if (kafkaConfig.isTopicCleanupCompact()) {
            if (cleanupPolicy.length() > 0) {
                cleanupPolicy.append(",");
            }
            cleanupPolicy.append("compact");
        }
        if (cleanupPolicy.length() > 0) {
            topicConfigs.put(TopicConfig.CLEANUP_POLICY_CONFIG, cleanupPolicy.toString());
        }

        topicConfigs.put(TopicConfig.DELETE_RETENTION_MS_CONFIG, String.valueOf(TimeUnit.HOURS.toMillis(24)));
        topicConfigs.put(TopicConfig.FILE_DELETE_DELAY_MS_CONFIG, String.valueOf(TimeUnit.MINUTES.toMillis(5)));

        try {
            TopicDescription existingTopic = adminClient.describeTopics(Collections.singleton(topicName))
                    .allTopicNames()
                    .get(30, TimeUnit.SECONDS)
                    .get(topicName);

            if (existingTopic != null) {
                log.info("Topic {} already exists, updating configurations", topicName);
                ConfigResource configResource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
                Config config = new Config(
                        topicConfigs.entrySet().stream()
                                .map(e -> new ConfigEntry(e.getKey(), e.getValue()))
                                .toList()
                );
                adminClient.alterConfigs(Collections.singletonMap(configResource, config));
                log.info("Topic {} configurations updated: retention={}h, retentionBytes={}GB, cleanup={}",
                        topicName,
                        kafkaConfig.getTopicRetentionHours(),
                        kafkaConfig.getTopicRetentionBytes() / (1024 * 1024 * 1024),
                        cleanupPolicy);
                return;
            }
        } catch (Exception e) {
            log.debug("Topic {} does not exist, will create it: {}", topicName, e.getMessage());
        }

        NewTopic newTopic = new NewTopic(topicName, numPartitions, replicationFactor)
                .configs(topicConfigs);

        adminClient.createTopics(Collections.singleton(newTopic))
                .all()
                .get(30, TimeUnit.SECONDS);

        log.info("Topic {} created successfully: partitions={}, replication={}, retention={}h, retentionBytes={}GB, cleanup={}",
                topicName,
                numPartitions,
                replicationFactor,
                kafkaConfig.getTopicRetentionHours(),
                kafkaConfig.getTopicRetentionBytes() / (1024 * 1024 * 1024),
                cleanupPolicy);
    }
}
