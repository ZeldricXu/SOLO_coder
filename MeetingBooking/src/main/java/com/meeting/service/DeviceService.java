package com.meeting.service;

import com.meeting.entity.Device;
import com.meeting.exception.MeetingException;
import com.meeting.repository.DeviceRepository;
import com.meeting.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;

    private static final String DEVICE_STATUS_AVAILABLE = "available";
    private static final String DEVICE_STATUS_IN_USE = "in_use";
    private static final String DEVICE_STATUS_MAINTENANCE = "maintenance";
    private static final String DEVICE_STATUS_BROKEN = "broken";

    public List<Device> getAllDevices() {
        return deviceRepository.findAll();
    }

    public Device getDeviceById(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new MeetingException(404, "设备不存在: " + deviceId));
    }

    public List<Device> getDevicesByRoomId(String roomId) {
        return deviceRepository.findByRoomId(roomId);
    }

    public List<Device> getDevicesByStatus(String status) {
        return deviceRepository.findByDeviceStatus(status);
    }

    public List<Device> getAvailableDevicesByRoomId(String roomId) {
        return deviceRepository.findAvailableDevicesByRoomId(roomId);
    }

    @Transactional
    public Device createDevice(Device device) {
        if (device.getDeviceId() == null || device.getDeviceId().isEmpty()) {
            device.setDeviceId(IdGenerator.generateDeviceId());
        }
        if (device.getDeviceStatus() == null || device.getDeviceStatus().isEmpty()) {
            device.setDeviceStatus(DEVICE_STATUS_AVAILABLE);
        }

        log.info("创建设备: deviceId={}, deviceType={}", device.getDeviceId(), device.getDeviceType());
        return deviceRepository.save(device);
    }

    @Transactional
    public Device updateDevice(String deviceId, Device deviceUpdate) {
        Device existingDevice = getDeviceById(deviceId);

        if (deviceUpdate.getRoomId() != null) {
            existingDevice.setRoomId(deviceUpdate.getRoomId());
        }
        if (deviceUpdate.getDeviceType() != null) {
            existingDevice.setDeviceType(deviceUpdate.getDeviceType());
        }
        if (deviceUpdate.getDeviceName() != null) {
            existingDevice.setDeviceName(deviceUpdate.getDeviceName());
        }
        if (deviceUpdate.getDeviceStatus() != null) {
            existingDevice.setDeviceStatus(deviceUpdate.getDeviceStatus());
        }
        if (deviceUpdate.getDeviceFeatures() != null) {
            existingDevice.setDeviceFeatures(deviceUpdate.getDeviceFeatures());
        }

        log.info("更新设备: deviceId={}", deviceId);
        return deviceRepository.save(existingDevice);
    }

    @Transactional
    public void deleteDevice(String deviceId) {
        Device device = getDeviceById(deviceId);
        log.info("删除设备: deviceId={}", deviceId);
        deviceRepository.delete(device);
    }

    @Transactional
    public Device updateDeviceStatus(String deviceId, String status) {
        Device device = getDeviceById(deviceId);
        device.setDeviceStatus(status);
        log.info("更新设备状态: deviceId={}, status={}", deviceId, status);
        return deviceRepository.save(device);
    }

    @Transactional
    public List<Device> checkAndOccupyRoomDevices(String roomId) {
        List<Device> devices = getAvailableDevicesByRoomId(roomId);
        for (Device device : devices) {
            device.setDeviceStatus(DEVICE_STATUS_IN_USE);
        }
        log.info("占用会议室设备: roomId={}, deviceCount={}", roomId, devices.size());
        return deviceRepository.saveAll(devices);
    }

    @Transactional
    public List<Device> releaseRoomDevices(String roomId) {
        List<Device> devices = deviceRepository.findByRoomIdAndDeviceStatus(roomId, DEVICE_STATUS_IN_USE);
        for (Device device : devices) {
            device.setDeviceStatus(DEVICE_STATUS_AVAILABLE);
        }
        log.info("释放会议室设备: roomId={}, deviceCount={}", roomId, devices.size());
        return deviceRepository.saveAll(devices);
    }

    public boolean checkRoomDevicesAvailable(String roomId) {
        List<Device> devices = deviceRepository.findByRoomId(roomId);
        if (devices.isEmpty()) {
            return true;
        }
        for (Device device : devices) {
            if (DEVICE_STATUS_BROKEN.equals(device.getDeviceStatus())) {
                log.warn("会议室设备故障: roomId={}, deviceId={}", roomId, device.getDeviceId());
            }
        }
        return devices.stream()
                .noneMatch(d -> DEVICE_STATUS_MAINTENANCE.equals(d.getDeviceStatus()));
    }

    @Transactional
    public Device markDeviceForMaintenance(String deviceId) {
        return updateDeviceStatus(deviceId, DEVICE_STATUS_MAINTENANCE);
    }

    @Transactional
    public Device completeDeviceMaintenance(String deviceId) {
        Device device = getDeviceById(deviceId);
        device.setDeviceStatus(DEVICE_STATUS_AVAILABLE);
        device.setLastMaintenance(java.time.LocalDateTime.now());
        return deviceRepository.save(device);
    }

    public long countAvailableDevices(String roomId) {
        return deviceRepository.countAvailableDevicesByRoomId(roomId);
    }
}
