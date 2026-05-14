package com.deviceops.service.device;

import com.deviceops.dto.DeviceCreateRequest;
import com.deviceops.entity.Device;
import com.deviceops.exception.DeviceOpsException;
import com.deviceops.repository.DeviceRepository;
import com.deviceops.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class DeviceService {

    @Autowired
    private DeviceRepository deviceRepository;

    @Transactional
    public Device createDevice(DeviceCreateRequest request) {
        Device device = new Device();
        device.setDeviceId(IdGenerator.generateDeviceId());
        device.setDeviceName(request.getDeviceName());
        device.setDeviceType(request.getDeviceType());
        device.setDeviceLocation(request.getDeviceLocation());
        device.setDeviceStatus("normal");
        device.setDeviceModel(request.getDeviceModel());
        device.setDeviceSn(request.getDeviceSn());
        return deviceRepository.save(device);
    }

    public Device getDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> DeviceOpsException.deviceNotFound(deviceId));
    }

    public List<Device> getAllDevices() {
        return deviceRepository.findAll();
    }

    public List<Device> getDevicesByType(String deviceType) {
        return deviceRepository.findByDeviceType(deviceType);
    }

    public List<Device> getDevicesByStatus(String deviceStatus) {
        return deviceRepository.findByDeviceStatus(deviceStatus);
    }

    @Transactional
    public Device updateDeviceStatus(String deviceId, String status) {
        Device device = getDevice(deviceId);
        device.setDeviceStatus(status);
        return deviceRepository.save(device);
    }

    @Transactional
    public Device updateDevice(String deviceId, DeviceCreateRequest request) {
        Device device = getDevice(deviceId);
        device.setDeviceName(request.getDeviceName());
        device.setDeviceType(request.getDeviceType());
        device.setDeviceLocation(request.getDeviceLocation());
        if (request.getDeviceModel() != null) {
            device.setDeviceModel(request.getDeviceModel());
        }
        if (request.getDeviceSn() != null) {
            device.setDeviceSn(request.getDeviceSn());
        }
        return deviceRepository.save(device);
    }

    @Transactional
    public void deleteDevice(String deviceId) {
        if (!deviceRepository.existsById(deviceId)) {
            throw DeviceOpsException.deviceNotFound(deviceId);
        }
        deviceRepository.deleteById(deviceId);
    }

    public boolean exists(String deviceId) {
        return deviceRepository.existsById(deviceId);
    }

    public long count() {
        return deviceRepository.count();
    }

    public long countByStatus(String status) {
        return deviceRepository.countByDeviceStatus(status);
    }
}
