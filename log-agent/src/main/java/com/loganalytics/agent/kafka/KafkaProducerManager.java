package com.loganalytics.agent.kafka;

import com.loganalytics.agent.config.AgentConfig;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.util.JsonUtils;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class KafkaProducerManager {
    private static final Logger log = LoggerFactory.getLogger(KafkaProducerManager.class);

    private final AgentConfig config;
    private KafkaProducer<String, byte[]> producer;
    private final List<LogEvent> batchBuffer;
    private long lastFlushTime;
    private final AtomicLong totalSent;
    private final AtomicLong totalFailed;
    private volatile boolean running;

    public KafkaProducerManager(AgentConfig config) {
        this.config = config;
        this.batchBuffer = new ArrayList<>();
        this.lastFlushTime = System.currentTimeMillis();
        this.totalSent = new AtomicLong(0);
        this.totalFailed = new AtomicLong(0);
        this.running = true;
    }

    public void start() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrapServers());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16 * 1024);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 64 * 1024 * 1024);
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 1 * 1024 * 1024);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, ServiceAwarePartitioner.class.getName());
        props.put("num.partitions", config.getKafkaPartitions());

        producer = new KafkaProducer<>(props);
        log.info("Kafka producer started for topic {}", config.getKafkaTopic());
    }

    public void send(LogEvent event) {
        if (!running) return;

        synchronized (batchBuffer) {
            batchBuffer.add(event);

            boolean shouldFlush = batchBuffer.size() >= config.getBatchSize()
                    || (System.currentTimeMillis() - lastFlushTime) >= config.getFlushInterval().toMillis();

            if (shouldFlush) {
                flushBatch();
            }
        }
    }

    private void flushBatch() {
        if (batchBuffer.isEmpty()) return;

        List<LogEvent> events = new ArrayList<>(batchBuffer);
        batchBuffer.clear();
        lastFlushTime = System.currentTimeMillis();

        for (LogEvent event : events) {
            try {
                String key = event.getPartitionKey();
                byte[] value = JsonUtils.toJson(event).getBytes();

                ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                        config.getKafkaTopic(), key, value);

                producer.send(record, (metadata, exception) -> {
                    if (exception != null) {
                        totalFailed.incrementAndGet();
                        log.error("Failed to send log event to Kafka", exception);
                    } else {
                        totalSent.incrementAndGet();
                        log.trace("Sent event to partition {} offset {}",
                                metadata.partition(), metadata.offset());
                    }
                });
            } catch (Exception e) {
                totalFailed.incrementAndGet();
                log.error("Error serializing log event", e);
            }
        }
    }

    public void flush() {
        synchronized (batchBuffer) {
            flushBatch();
        }
        producer.flush();
    }

    public long getTotalSent() {
        return totalSent.get();
    }

    public long getTotalFailed() {
        return totalFailed.get();
    }

    public void stop() {
        running = false;
        try {
            flush();
        } catch (Exception e) {
            log.error("Error flushing during shutdown", e);
        }
        try {
            producer.close(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Error closing producer", e);
        }
        log.info("Kafka producer stopped. Total sent: {}, failed: {}", totalSent.get(), totalFailed.get());
    }

    public static class ServiceAwarePartitioner implements Partitioner {
        private int numPartitions;

        @Override
        public void configure(Map<String, ?> configs) {
            Object partitions = configs.get("num.partitions");
            if (partitions instanceof Number) {
                this.numPartitions = ((Number) partitions).intValue();
            } else {
                this.numPartitions = 12;
            }
        }

        @Override
        public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
            if (key == null) {
                return 0;
            }
            return Math.abs(key.hashCode()) % numPartitions;
        }

        @Override
        public void close() {
        }

        @Override
        public void onNewBatch(String topic, Cluster cluster, int prevPartition) {
        }
    }
}
