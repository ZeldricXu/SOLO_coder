package com.loganalytics.api.service;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.util.JsonUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class LogStreamService {
    private static final Logger log = LoggerFactory.getLogger(LogStreamService.class);
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private KafkaConsumer<String, String> consumer;
    private volatile boolean running = true;

    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${kafka.topic.logs:structured-logs}")
    private String logTopic;

    @PostConstruct
    public void init() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "api-server-sse-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(logTopic));

        executorService.submit(this::consumeLogs);
        log.info("LogStreamService initialized, subscribing to topic: {}", logTopic);
    }

    private void consumeLogs() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<String, String> record : records) {
                    String logJson = record.value();
                    broadcastLog(logJson);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("Error consuming logs", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void broadcastLog(String logJson) {
        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("log")
                        .data(logJson));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }

    public SseEmitter createStream(String serviceName, String level, String patternId) {
        SseEmitter emitter = new SseEmitter(0L);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        emitters.add(emitter);
        log.debug("New SSE stream created, total emitters: {}", emitters.size());

        return emitter;
    }

    public void simulateLog(LogEvent event) {
        broadcastLog(JsonUtils.toJson(event));
    }

    @PreDestroy
    public void destroy() {
        running = false;
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
        if (consumer != null) {
            consumer.close();
        }
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
    }

    public int getActiveConnections() {
        return emitters.size();
    }
}
