package com.cardgame.replay.kafka;

import com.cardgame.common.config.KafkaConfig;
import com.cardgame.common.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class BattleLogProducer {

    private Producer<String, String> producer;

    @Autowired
    private KafkaConfig kafkaConfig;

    @PostConstruct
    public void init() {
        Properties props = new Properties();
        props.put("bootstrap.servers", kafkaConfig.getBootstrapServers());
        props.put("acks", kafkaConfig.getProducerAcks());
        props.put("retries", kafkaConfig.getProducerRetries());
        props.put("batch.size", kafkaConfig.getProducerBatchSize());
        props.put("linger.ms", kafkaConfig.getProducerLingerMs());
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        producer = new KafkaProducer<>(props);
        log.info("Kafka producer initialized for topic: {}", kafkaConfig.getBattleLogTopic());
    }

    @PreDestroy
    public void close() {
        if (producer != null) {
            producer.close();
            log.info("Kafka producer closed");
        }
    }

    public void sendBattleLog(String battleId, Object battleLogData) {
        try {
            String json = JsonUtils.toJson(battleLogData);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    kafkaConfig.getBattleLogTopic(),
                    battleId,
                    json
            );

            CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to send battle log {} to Kafka: {}", battleId, exception.getMessage());
                    future.completeExceptionally(exception);
                } else {
                    log.debug("Sent battle log {} to Kafka partition {}, offset {}",
                            battleId, metadata.partition(), metadata.offset());
                    future.complete(metadata);
                }
            });
        } catch (Exception e) {
            log.error("Error serializing battle log {}: {}", battleId, e.getMessage());
        }
    }

    public void sendBattleLogAsync(String battleId, Object battleLogData) {
        CompletableFuture.runAsync(() -> sendBattleLog(battleId, battleLogData));
    }
}
