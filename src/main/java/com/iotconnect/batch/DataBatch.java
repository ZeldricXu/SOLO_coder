package com.iotconnect.batch;

import com.iotconnect.entity.Device;
import com.iotconnect.entity.DeviceData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class DataBatch {

    private final String batchId;
    private final List<BatchItem> items;
    private final Instant createdAt;
    private Instant lastUpdatedAt;

    public DataBatch() {
        this.batchId = generateBatchId();
        this.items = new ArrayList<>();
        this.createdAt = Instant.now();
        this.lastUpdatedAt = Instant.now();
    }

    public synchronized void addItem(Device device, DeviceData deviceData) {
        this.items.add(new BatchItem(device, deviceData));
        this.lastUpdatedAt = Instant.now();
    }

    public synchronized List<BatchItem> getItems() {
        return new ArrayList<>(items);
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized boolean isEmpty() {
        return items.isEmpty();
    }

    public synchronized void clear() {
        items.clear();
    }

    public String getBatchId() {
        return batchId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    private String generateBatchId() {
        return "batch_" + System.currentTimeMillis() + "_" + 
               java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    public static class BatchItem {
        private final Device device;
        private final DeviceData deviceData;

        public BatchItem(Device device, DeviceData deviceData) {
            this.device = device;
            this.deviceData = deviceData;
        }

        public Device getDevice() {
            return device;
        }

        public DeviceData getDeviceData() {
            return deviceData;
        }
    }
}
