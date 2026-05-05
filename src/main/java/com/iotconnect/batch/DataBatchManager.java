package com.iotconnect.batch;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.iotconnect.config.InfluxDBConfig;
import com.iotconnect.entity.Device;
import com.iotconnect.entity.DeviceData;
import com.iotconnect.service.BatchConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

@Component
public class DataBatchManager {

    private static final Logger logger = LoggerFactory.getLogger(DataBatchManager.class);

    private final InfluxDBClient influxDBClient;
    private final InfluxDBConfig influxDBConfig;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final BatchConfigService batchConfigService;

    private final Map<String, DeviceTypeBuffer> deviceTypeBuffers = new ConcurrentHashMap<>();
    
    private final Lock flushLock = new ReentrantLock();
    private final AtomicBoolean isFlushing = new AtomicBoolean(false);

    private Consumer<List<DataBatch.BatchItem>> postFlushCallback;

    public DataBatchManager(InfluxDBClient influxDBClient,
                            InfluxDBConfig influxDBConfig,
                            KafkaTemplate<String, String> kafkaTemplate,
                            BatchConfigService batchConfigService) {
        this.influxDBClient = influxDBClient;
        this.influxDBConfig = influxDBConfig;
        this.kafkaTemplate = kafkaTemplate;
        this.batchConfigService = batchConfigService;
    }

    @PostConstruct
    public void init() {
        BatchConfigService.BatchConfig defaultConfig = batchConfigService.getDefaultConfig();
        logger.info("DataBatchManager initialized with default config: batchSize={}, windowSeconds={}",
                defaultConfig.getBatchSize(), defaultConfig.getWindowSeconds());
    }

    @PreDestroy
    public void shutdown() {
        logger.info("DataBatchManager shutting down, flushing all remaining data...");
        forceFlushAll();
    }

    public void setPostFlushCallback(Consumer<List<DataBatch.BatchItem>> callback) {
        this.postFlushCallback = callback;
    }

    public boolean addData(Device device, DeviceData deviceData) {
        String deviceType = device.getDeviceType();
        
        DeviceTypeBuffer buffer = getOrCreateBuffer(deviceType);
        
        if (buffer.getCurrentSize() >= buffer.getConfig().getMaxBufferSize()) {
            logger.warn("Buffer overflow for deviceType={}, force flushing current buffer", deviceType);
            forceFlushByType(deviceType);
        }

        DataBatch.BatchItem item = new DataBatch.BatchItem(device, deviceData);
        buffer.addItem(item);

        logger.debug("Item added to buffer: deviceId={}, deviceType={}, dataType={}, currentSize={}",
                deviceData.getDeviceId(), deviceType, deviceData.getDataType(), buffer.getCurrentSize());

        if (buffer.shouldFlushBySize()) {
            triggerFlushByType(deviceType);
        }

        return true;
    }

    private DeviceTypeBuffer getOrCreateBuffer(String deviceType) {
        return deviceTypeBuffers.computeIfAbsent(deviceType, type -> {
            BatchConfigService.BatchConfig config = batchConfigService.getConfig(type);
            logger.info("Created new buffer for deviceType={} with config: batchSize={}, windowSeconds={}",
                    type, config.getBatchSize(), config.getWindowSeconds());
            return new DeviceTypeBuffer(type, config);
        });
    }

    @Scheduled(fixedDelayString = "${batch.processing.flush-interval-seconds:10000}")
    public void scheduledFlush() {
        Instant now = Instant.now();

        for (Map.Entry<String, DeviceTypeBuffer> entry : deviceTypeBuffers.entrySet()) {
            String deviceType = entry.getKey();
            DeviceTypeBuffer buffer = entry.getValue();
            
            if (buffer.shouldFlushByTime(now)) {
                logger.debug("Scheduled flush triggered for deviceType={}: bufferSize={}, timeSinceLastFlush={}s",
                        deviceType, buffer.getCurrentSize(),
                        java.time.Duration.between(buffer.getLastFlushTime(), now).getSeconds());
                
                forceFlushByType(deviceType);
            }
        }
    }

    public void forceFlushByType(String deviceType) {
        DeviceTypeBuffer buffer = deviceTypeBuffers.get(deviceType);
        
        if (buffer == null || buffer.getCurrentSize() == 0) {
            return;
        }

        if (!flushLock.tryLock()) {
            logger.debug("Flush already in progress, skipping for deviceType={}", deviceType);
            return;
        }

        try {
            isFlushing.set(true);

            List<DataBatch.BatchItem> itemsToFlush = buffer.drainItems();

            if (!itemsToFlush.isEmpty()) {
                logger.info("Flushing batch for deviceType={}: size={}", deviceType, itemsToFlush.size());
                
                long startTime = System.currentTimeMillis();
                flushToInfluxDB(itemsToFlush);
                flushToKafka(itemsToFlush);
                
                if (postFlushCallback != null) {
                    postFlushCallback.accept(itemsToFlush);
                }
                
                long duration = System.currentTimeMillis() - startTime;
                logger.info("Batch flushed successfully for deviceType={}: size={}, duration={}ms", 
                        deviceType, itemsToFlush.size(), duration);
                
                buffer.updateLastFlushTime();
            }

        } catch (Exception e) {
            logger.error("Failed to flush batch for deviceType={}: {}", deviceType, e.getMessage(), e);
        } finally {
            isFlushing.set(false);
            flushLock.unlock();
        }
    }

    public void forceFlushAll() {
        for (String deviceType : new ArrayList<>(deviceTypeBuffers.keySet())) {
            forceFlushByType(deviceType);
        }
    }

    public void forceFlush() {
        forceFlushAll();
    }

    private void triggerFlushByType(String deviceType) {
        if (isFlushing.get()) {
            return;
        }
        forceFlushByType(deviceType);
    }

    private void flushToInfluxDB(List<DataBatch.BatchItem> items) {
        if (items.isEmpty()) {
            return;
        }

        try (WriteApi writeApi = influxDBClient.makeWriteApi()) {
            List<Point> points = new ArrayList<>(items.size());
            
            for (DataBatch.BatchItem item : items) {
                DeviceData deviceData = item.getDeviceData();
                
                Point point = Point.measurement("device_data")
                        .addTag("device_id", deviceData.getDeviceId())
                        .addTag("data_type", deviceData.getDataType())
                        .addField("value", deviceData.getValue())
                        .addField("quality", deviceData.getQuality())
                        .time(deviceData.getCollectedAt(), WritePrecision.MS);

                if (deviceData.getUnit() != null && !deviceData.getUnit().isEmpty()) {
                    point.addTag("unit", deviceData.getUnit());
                }
                
                points.add(point);
            }

            writeApi.writePoints(points);
            logger.debug("Successfully wrote {} points to InfluxDB", points.size());

        } catch (Exception e) {
            logger.error("Failed to write batch to InfluxDB: {}", e.getMessage(), e);
            throw e;
        }
    }

    private void flushToKafka(List<DataBatch.BatchItem> items) {
        if (items.isEmpty()) {
            return;
        }

        for (DataBatch.BatchItem item : items) {
            try {
                DeviceData deviceData = item.getDeviceData();
                String message = String.format(
                        "{\"data_id\":\"%s\",\"device_id\":\"%s\",\"data_type\":\"%s\",\"value\":%f,\"collected_at\":\"%s\"}",
                        deviceData.getDataId(),
                        deviceData.getDeviceId(),
                        deviceData.getDataType(),
                        deviceData.getValue(),
                        deviceData.getCollectedAt().toString()
                );

                kafkaTemplate.send("iot-device-data", deviceData.getDeviceId(), message);
                logger.debug("Message published to Kafka: deviceId={}", deviceData.getDeviceId());

            } catch (Exception e) {
                logger.warn("Failed to publish to Kafka: {}", e.getMessage());
            }
        }
    }

    public int getCurrentBufferSize() {
        return deviceTypeBuffers.values().stream()
                .mapToInt(DeviceTypeBuffer::getCurrentSize)
                .sum();
    }

    public int getCurrentBufferSize(String deviceType) {
        DeviceTypeBuffer buffer = deviceTypeBuffers.get(deviceType);
        return buffer != null ? buffer.getCurrentSize() : 0;
    }

    public boolean isFlushing() {
        return isFlushing.get();
    }

    public Map<String, Integer> getBufferSizesByType() {
        Map<String, Integer> sizes = new ConcurrentHashMap<>();
        for (Map.Entry<String, DeviceTypeBuffer> entry : deviceTypeBuffers.entrySet()) {
            sizes.put(entry.getKey(), entry.getValue().getCurrentSize());
        }
        return sizes;
    }

    public void refreshConfig(String deviceType) {
        DeviceTypeBuffer buffer = deviceTypeBuffers.get(deviceType);
        if (buffer != null) {
            BatchConfigService.BatchConfig newConfig = batchConfigService.getConfig(deviceType);
            buffer.updateConfig(newConfig);
            logger.info("Refreshed config for deviceType={}: {}", deviceType, newConfig);
        }
    }

    private static class DeviceTypeBuffer {
        private final String deviceType;
        private volatile BatchConfigService.BatchConfig config;
        private final ConcurrentLinkedQueue<DataBatch.BatchItem> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger(0);
        private volatile Instant lastFlushTime = Instant.now();

        public DeviceTypeBuffer(String deviceType, BatchConfigService.BatchConfig config) {
            this.deviceType = deviceType;
            this.config = config;
        }

        public void addItem(DataBatch.BatchItem item) {
            queue.offer(item);
            size.incrementAndGet();
        }

        public List<DataBatch.BatchItem> drainItems() {
            List<DataBatch.BatchItem> items = new ArrayList<>();
            DataBatch.BatchItem item;
            
            int maxItems = config.getBatchSize();
            while ((item = queue.poll()) != null && items.size() < maxItems) {
                items.add(item);
                size.decrementAndGet();
            }
            
            return items;
        }

        public boolean shouldFlushBySize() {
            return size.get() >= config.getBatchSize();
        }

        public boolean shouldFlushByTime(Instant now) {
            return size.get() > 0 && 
                    lastFlushTime.plusSeconds(config.getWindowSeconds()).isBefore(now);
        }

        public int getCurrentSize() {
            return size.get();
        }

        public Instant getLastFlushTime() {
            return lastFlushTime;
        }

        public void updateLastFlushTime() {
            this.lastFlushTime = Instant.now();
        }

        public BatchConfigService.BatchConfig getConfig() {
            return config;
        }

        public void updateConfig(BatchConfigService.BatchConfig config) {
            this.config = config;
        }
    }
}
