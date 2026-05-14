package com.iotconnect.service;

import com.iotconnect.async.AlertDetectionResult;
import com.iotconnect.async.AsyncAlertDetectionService;
import com.iotconnect.batch.DataBatch;
import com.iotconnect.batch.DataBatchManager;
import com.iotconnect.dto.DataReportRequest;
import com.iotconnect.dto.DataReportResponse;
import com.iotconnect.entity.Device;
import com.iotconnect.entity.DeviceData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class DataCollectionService {

    private static final Logger logger = LoggerFactory.getLogger(DataCollectionService.class);

    private final DataBatchManager dataBatchManager;
    private final AsyncAlertDetectionService asyncAlertDetectionService;
    private final DeviceService deviceService;
    private final ConnectionService connectionService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public DataCollectionService(DataBatchManager dataBatchManager,
                                  AsyncAlertDetectionService asyncAlertDetectionService,
                                  DeviceService deviceService,
                                  ConnectionService connectionService,
                                  KafkaTemplate<String, String> kafkaTemplate) {
        this.dataBatchManager = dataBatchManager;
        this.asyncAlertDetectionService = asyncAlertDetectionService;
        this.deviceService = deviceService;
        this.connectionService = connectionService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostConstruct
    public void init() {
        dataBatchManager.setPostFlushCallback(this::processPostFlush);
        logger.info("DataCollectionService initialized with batch processing: enabled={}",
                dataBatchManager.isBatchProcessingEnabled());
    }

    public DataReportResponse collectData(DataReportRequest request) {
        validateRequest(request);

        Optional<Device> deviceOpt = deviceService.findByDeviceId(request.getDeviceId());
        if (deviceOpt.isEmpty()) {
            throw new RuntimeException("Device not found: " + request.getDeviceId());
        }

        Device device = deviceOpt.get();

        if (!connectionService.isDeviceOnline(request.getDeviceId())) {
            throw new RuntimeException("Device is not online: " + request.getDeviceId());
        }

        DeviceData deviceData = parseAndValidateData(request, device);

        boolean added = dataBatchManager.addData(device, deviceData);
        
        if (!added) {
            logger.warn("Failed to add data to batch: deviceId={}", device.getDeviceId());
        }

        deviceService.updateLastActive(request.getDeviceId());

        asyncAlertDetectionService.processDetectionAsync(device, deviceData);

        logger.info("Data queued for collection: deviceId={}, dataType={}, value={}, batchSize={}",
                deviceData.getDeviceId(), deviceData.getDataType(), deviceData.getValue(),
                dataBatchManager.getCurrentBufferSize());

        return new DataReportResponse(deviceData.getDataId());
    }

    public DataReportResponse collectDataImmediate(DataReportRequest request) {
        validateRequest(request);

        Optional<Device> deviceOpt = deviceService.findByDeviceId(request.getDeviceId());
        if (deviceOpt.isEmpty()) {
            throw new RuntimeException("Device not found: " + request.getDeviceId());
        }

        Device device = deviceOpt.get();

        if (!connectionService.isDeviceOnline(request.getDeviceId())) {
            throw new RuntimeException("Device is not online: " + request.getDeviceId());
        }

        DeviceData deviceData = parseAndValidateData(request, device);

        dataBatchManager.addData(device, deviceData);
        dataBatchManager.forceFlush();

        deviceService.updateLastActive(request.getDeviceId());

        asyncAlertDetectionService.processDetectionAsync(device, deviceData);

        logger.info("Data collected immediately (force flush): deviceId={}, dataType={}, value={}",
                deviceData.getDeviceId(), deviceData.getDataType(), deviceData.getValue());

        return new DataReportResponse(deviceData.getDataId());
    }

    private void processPostFlush(List<DataBatch.BatchItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        logger.debug("Processing post-flush for {} items", items.size());

        for (DataBatch.BatchItem item : items) {
            try {
                Device device = item.getDevice();
                DeviceData deviceData = item.getDeviceData();
                
                deviceService.updateLastActive(device.getDeviceId());
                
            } catch (Exception e) {
                logger.warn("Post-flush processing error for device {}: {}", 
                        item.getDeviceData().getDeviceId(), e.getMessage());
            }
        }
    }

    private void validateRequest(DataReportRequest request) {
        if (request.getDeviceId() == null || request.getDeviceId().trim().isEmpty()) {
            throw new IllegalArgumentException("Device ID cannot be null or empty");
        }
        if (request.getDataType() == null || request.getDataType().trim().isEmpty()) {
            throw new IllegalArgumentException("Data type cannot be null or empty");
        }
        if (request.getValue() == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
    }

    private DeviceData parseAndValidateData(DataReportRequest request, Device device) {
        DeviceData deviceData = new DeviceData();
        deviceData.setDataId(generateDataId());
        deviceData.setDeviceId(request.getDeviceId());
        deviceData.setDataType(request.getDataType());
        deviceData.setValue(request.getValue());
        deviceData.setUnit(request.getUnit() != null ? request.getUnit() : "");
        deviceData.setCollectedAt(request.getTimestamp() != null 
                ? Instant.ofEpochMilli(request.getTimestamp()) 
                : Instant.now());
        
        deviceData.setQuality(validateDataQuality(deviceData));

        return deviceData;
    }

    private String validateDataQuality(DeviceData deviceData) {
        if (deviceData.getValue() == null) {
            return "invalid";
        }
        
        if (Double.isNaN(deviceData.getValue()) || Double.isInfinite(deviceData.getValue())) {
            return "invalid";
        }
        
        return "good";
    }

    public int getCurrentBufferSize() {
        return dataBatchManager.getCurrentBufferSize();
    }

    public void forceFlush() {
        dataBatchManager.forceFlush();
    }

    public boolean isBatchProcessingEnabled() {
        return dataBatchManager.isBatchProcessingEnabled();
    }

    private String generateDataId() {
        return "data_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
