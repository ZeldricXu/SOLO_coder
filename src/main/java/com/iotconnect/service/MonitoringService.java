package com.iotconnect.service;

import com.iotconnect.dto.*;
import com.iotconnect.entity.AlertEvent;
import com.iotconnect.enums.AlertSeverity;
import com.iotconnect.enums.ConnectionStatus;
import com.iotconnect.repository.AlertEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MonitoringService {

    private static final Logger logger = LoggerFactory.getLogger(MonitoringService.class);

    private final DeviceService deviceService;
    private final ConnectionService connectionService;
    private final AlertEventRepository alertEventRepository;

    public MonitoringService(DeviceService deviceService, ConnectionService connectionService,
                             AlertEventRepository alertEventRepository) {
        this.deviceService = deviceService;
        this.connectionService = connectionService;
        this.alertEventRepository = alertEventRepository;
    }

    public DeviceStatusStatistics getDeviceStatusStatistics() {
        DeviceStatusStatistics stats = new DeviceStatusStatistics();

        stats.setTotalDevices(deviceService.getDeviceCount());
        stats.setOnlineDevices(deviceService.getOnlineDeviceCount());
        stats.setOfflineDevices(deviceService.getOfflineDeviceCount());
        stats.setOnlineRate(calculateRate(stats.getOnlineDevices(), stats.getTotalDevices()));

        long activeAlerts = alertEventRepository.countActiveAlerts();
        stats.setActiveAlerts(activeAlerts);

        stats.setCriticalAlerts(alertEventRepository.countActiveAlertsBySeverity(AlertSeverity.CRITICAL.getValue()));
        stats.setHighAlerts(alertEventRepository.countActiveAlertsBySeverity(AlertSeverity.HIGH.getValue()));
        stats.setMediumAlerts(alertEventRepository.countActiveAlertsBySeverity(AlertSeverity.MEDIUM.getValue()));
        stats.setLowAlerts(alertEventRepository.countActiveAlertsBySeverity(AlertSeverity.LOW.getValue()));

        logger.info("Device status statistics retrieved");
        return stats;
    }

    public DeviceDetailStatus getDeviceDetailStatus(String deviceId) {
        DeviceDetailStatus status = new DeviceDetailStatus();

        var deviceOpt = deviceService.findByDeviceId(deviceId);
        if (deviceOpt.isEmpty()) {
            throw new RuntimeException("Device not found: " + deviceId);
        }

        var device = deviceOpt.get();
        status.setDeviceId(device.getDeviceId());
        status.setDeviceName(device.getDeviceName());
        status.setDeviceType(device.getDeviceType());
        status.setDeviceGroup(device.getDeviceGroup());
        status.setConnectionStatus(device.getConnectionStatus());
        status.setLastActive(device.getLastActive());
        status.setRegisteredAt(device.getRegisteredAt());

        var connectionOpt = connectionService.getConnection(deviceId);
        connectionOpt.ifPresent(conn -> {
            status.setLastHeartbeat(conn.getLastHeartbeat());
            status.setConnectionTime(conn.getConnectionTime());
            status.setClientAddress(conn.getClientAddress());
        });

        List<AlertEvent> activeAlerts = alertEventRepository.findActiveAlertsByDeviceId(deviceId);
        status.setActiveAlerts(activeAlerts.size());

        if (!activeAlerts.isEmpty()) {
            Map<String, Integer> alertSeverityCount = new HashMap<>();
            for (AlertEvent alert : activeAlerts) {
                alertSeverityCount.put(alert.getSeverity(), 
                    alertSeverityCount.getOrDefault(alert.getSeverity(), 0) + 1);
            }
            status.setAlertSeverityDistribution(alertSeverityCount);
        }

        logger.debug("Device detail status retrieved: deviceId={}", deviceId);
        return status;
    }

    public GroupStatusStatistics getGroupStatusStatistics(String deviceGroup) {
        GroupStatusStatistics stats = new GroupStatusStatistics();

        var devices = deviceService.getDevicesByGroup(deviceGroup);
        stats.setDeviceGroup(deviceGroup);
        stats.setTotalDevices(devices.size());

        long onlineCount = devices.stream()
                .filter(d -> ConnectionStatus.ONLINE.getValue().equals(d.getConnectionStatus()))
                .count();
        stats.setOnlineDevices(onlineCount);
        stats.setOfflineDevices(devices.size() - onlineCount);
        stats.setOnlineRate(calculateRate(onlineCount, devices.size()));

        logger.debug("Group status statistics retrieved: group={}", deviceGroup);
        return stats;
    }

    public Map<String, Long> getDeviceTypeDistribution() {
        var allDevices = deviceService.getAllDevices();
        Map<String, Long> distribution = new HashMap<>();
        
        for (var device : allDevices) {
            String type = device.getDeviceType();
            distribution.put(type, distribution.getOrDefault(type, 0L) + 1);
        }

        logger.debug("Device type distribution retrieved");
        return distribution;
    }

    public SystemOverview getSystemOverview() {
        SystemOverview overview = new SystemOverview();

        overview.setTotalDevices(deviceService.getDeviceCount());
        overview.setOnlineDevices(deviceService.getOnlineDeviceCount());
        overview.setActiveAlerts(alertEventRepository.countActiveAlerts());
        overview.setCriticalAlerts(alertEventRepository.countActiveAlertsBySeverity(AlertSeverity.CRITICAL.getValue()));

        logger.info("System overview retrieved");
        return overview;
    }

    private double calculateRate(long value, long total) {
        if (total == 0) {
            return 0.0;
        }
        return (double) value / total * 100;
    }
}
