package com.deviceops.service.monitor;

import com.deviceops.entity.StatusRecord;
import com.deviceops.exception.DeviceOpsException;
import com.deviceops.repository.StatusRecordRepository;
import com.deviceops.service.device.DeviceService;
import com.deviceops.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class MonitorService {

    @Autowired
    private StatusRecordRepository statusRecordRepository;

    @Autowired
    private DeviceService deviceService;

    private final Random random = new Random();

    @Transactional
    public Map<String, Integer> getLatestStatus(String deviceId) {
        if (!deviceService.exists(deviceId)) {
            throw DeviceOpsException.deviceNotFound(deviceId);
        }

        List<StatusRecord> records = statusRecordRepository.findTop10ByDeviceIdOrderByStatusTimeDesc(deviceId);
        Map<String, Integer> statusMap = new HashMap<>();

        for (StatusRecord record : records) {
            if (!statusMap.containsKey(record.getStatusType())) {
                statusMap.put(record.getStatusType(), record.getStatusValue());
            }
        }

        if (statusMap.isEmpty()) {
            statusMap.put("cpu", 50 + random.nextInt(30));
            statusMap.put("memory", 40 + random.nextInt(40));
        }

        return statusMap;
    }

    @Transactional
    public List<StatusRecord> getStatusHistory(String deviceId) {
        if (!deviceService.exists(deviceId)) {
            throw DeviceOpsException.deviceNotFound(deviceId);
        }
        return statusRecordRepository.findByDeviceIdOrderByStatusTimeDesc(deviceId);
    }

    @Transactional
    public StatusRecord collectStatus(String deviceId, String statusType) {
        if (!deviceService.exists(deviceId)) {
            throw DeviceOpsException.deviceNotFound(deviceId);
        }

        int value = generateStatusValue(statusType);
        String level = determineStatusLevel(statusType, value);

        StatusRecord record = new StatusRecord();
        record.setStatusId(IdGenerator.generateStatusId());
        record.setDeviceId(deviceId);
        record.setStatusType(statusType);
        record.setStatusValue(value);
        record.setStatusTime(LocalDateTime.now());
        record.setStatusLevel(level);

        return statusRecordRepository.save(record);
    }

    @Transactional
    public Map<String, StatusRecord> collectAllStatus(String deviceId) {
        Map<String, StatusRecord> results = new HashMap<>();
        String[] types = {"cpu", "memory", "network"};

        for (String type : types) {
            StatusRecord record = collectStatus(deviceId, type);
            results.put(type, record);
        }

        updateDeviceStatusBasedOnRecords(deviceId, results);

        return results;
    }

    private void updateDeviceStatusBasedOnRecords(String deviceId, Map<String, StatusRecord> records) {
        boolean hasAbnormal = records.values().stream()
                .anyMatch(r -> "abnormal".equals(r.getStatusLevel()));
        boolean hasWarning = records.values().stream()
                .anyMatch(r -> "warning".equals(r.getStatusLevel()));

        String newStatus;
        if (hasAbnormal) {
            newStatus = "abnormal";
        } else if (hasWarning) {
            newStatus = "warning";
        } else {
            newStatus = "normal";
        }

        deviceService.updateDeviceStatus(deviceId, newStatus);
    }

    private int generateStatusValue(String statusType) {
        switch (statusType) {
            case "cpu":
                return 30 + random.nextInt(70);
            case "memory":
                return 40 + random.nextInt(50);
            case "network":
                return 20 + random.nextInt(60);
            default:
                return 50;
        }
    }

    private String determineStatusLevel(String statusType, int value) {
        if (value >= 90) {
            return "abnormal";
        } else if (value >= 70) {
            return "warning";
        } else {
            return "normal";
        }
    }

    public boolean hasAbnormalStatus(String deviceId) {
        List<StatusRecord> records = statusRecordRepository.findTop10ByDeviceIdOrderByStatusTimeDesc(deviceId);
        return records.stream().anyMatch(r -> "abnormal".equals(r.getStatusLevel()));
    }

    public String determineDeviceStatus(String deviceId) {
        List<StatusRecord> records = statusRecordRepository.findTop10ByDeviceIdOrderByStatusTimeDesc(deviceId);
        if (records.isEmpty()) {
            return "normal";
        }

        boolean hasAbnormal = records.stream().anyMatch(r -> "abnormal".equals(r.getStatusLevel()));
        boolean hasWarning = records.stream().anyMatch(r -> "warning".equals(r.getStatusLevel()));

        if (hasAbnormal) {
            return "abnormal";
        } else if (hasWarning) {
            return "warning";
        } else {
            return "normal";
        }
    }
}
