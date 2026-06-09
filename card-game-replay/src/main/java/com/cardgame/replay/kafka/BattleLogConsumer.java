package com.cardgame.replay.kafka;

import com.cardgame.common.config.KafkaConfig;
import com.cardgame.common.utils.JsonUtils;
import com.cardgame.replay.entity.BattleLog;
import com.cardgame.replay.service.BattleLogService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class BattleLogConsumer {

    private Consumer<String, String> consumer;
    private Thread consumerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    private KafkaConfig kafkaConfig;

    @Autowired
    private BattleLogService battleLogService;

    @PostConstruct
    public void init() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaConfig.getGroupId());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(kafkaConfig.getBattleLogTopic()));

        running.set(true);
        consumerThread = new Thread(this::run, "battle-log-consumer");
        consumerThread.start();

        log.info("Kafka consumer started for topic: {}", kafkaConfig.getBattleLogTopic());
    }

    @PreDestroy
    public void close() {
        running.set(false);
        if (consumerThread != null) {
            consumerThread.interrupt();
            try {
                consumerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (consumer != null) {
            consumer.close();
        }
        log.info("Kafka consumer closed");
    }

    private void run() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<String, String> record : records) {
                    processRecord(record);
                }
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                log.error("Error in Kafka consumer: {}", e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        try {
            String battleId = record.key();
            String json = record.value();

            BattleLog battleLog = JsonUtils.fromJson(json, BattleLog.class);
            if (battleLog != null) {
                battleLog.calculateStats();
                battleLogService.saveBattleLog(battleLog);
                log.debug("Processed battle log {} from Kafka", battleId);
            }
        } catch (Exception e) {
            log.error("Error processing battle log record: {}", e.getMessage());
        }
    }
}
