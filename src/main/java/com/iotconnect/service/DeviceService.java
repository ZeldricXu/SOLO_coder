package com.iotconnect.service;

import com.iotconnect.dto.DeviceRegisterRequest;
import com.iotconnect.dto.DeviceRegisterResponse;
import com.iotconnect.entity.Device;
import com.iotconnect.enums.ConnectionStatus;
import com.iotconnect.repository.DeviceRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeviceService {

    private static final Logger logger = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository deviceRepository;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration}")
    private Long jwtExpiration;

    public DeviceService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Transactional
    public DeviceRegisterResponse registerDevice(DeviceRegisterRequest request) {
        String deviceId = generateDeviceId(request.getDeviceType());
        String authToken = generateAuthToken(deviceId);

        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setDeviceName(request.getDeviceName());
        device.setDeviceType(request.getDeviceType());
        device.setDeviceGroup(request.getDeviceGroup());
        device.setProtocol(request.getProtocol());
        device.setConnectionStatus(ConnectionStatus.OFFLINE.getValue());
        device.setAuthToken(authToken);
        device.setRegisteredAt(LocalDateTime.now());

        Device savedDevice = deviceRepository.save(device);
        logger.info("Device registered: deviceId={}, deviceName={}", savedDevice.getDeviceId(), savedDevice.getDeviceName());

        return new DeviceRegisterResponse(savedDevice.getDeviceId(), savedDevice.getAuthToken());
    }

    @Transactional
    public Device updateDevice(String deviceId, DeviceRegisterRequest request) {
        Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isEmpty()) {
            throw new RuntimeException("Device not found: " + deviceId);
        }

        Device device = deviceOpt.get();
        device.setDeviceName(request.getDeviceName());
        if (request.getDeviceGroup() != null) {
            device.setDeviceGroup(request.getDeviceGroup());
        }
        if (request.getProtocol() != null) {
            device.setProtocol(request.getProtocol());
        }

        Device updatedDevice = deviceRepository.save(device);
        logger.info("Device updated: deviceId={}", updatedDevice.getDeviceId());

        return updatedDevice;
    }

    @Transactional
    public void deleteDevice(String deviceId) {
        if (!deviceRepository.existsById(deviceId)) {
            throw new RuntimeException("Device not found: " + deviceId);
        }
        deviceRepository.deleteById(deviceId);
        logger.info("Device deleted: deviceId={}", deviceId);
    }

    public Device getDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found: " + deviceId));
    }

    public Optional<Device> findByDeviceId(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId);
    }

    public boolean validateDeviceToken(String deviceId, String authToken) {
        return deviceRepository.findByDeviceIdAndAuthToken(deviceId, authToken).isPresent();
    }

    public List<Device> getAllDevices() {
        return deviceRepository.findAll();
    }

    public List<Device> getDevicesByStatus(String status) {
        return deviceRepository.findByConnectionStatus(status);
    }

    public List<Device> getDevicesByType(String deviceType) {
        return deviceRepository.findByDeviceType(deviceType);
    }

    public List<Device> getDevicesByGroup(String deviceGroup) {
        return deviceRepository.findByDeviceGroup(deviceGroup);
    }

    @Transactional
    public void updateConnectionStatus(String deviceId, ConnectionStatus status) {
        Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isPresent()) {
            Device device = deviceOpt.get();
            device.setConnectionStatus(status.getValue());
            if (status == ConnectionStatus.ONLINE) {
                device.setLastActive(LocalDateTime.now());
            }
            deviceRepository.save(device);
            logger.debug("Device connection status updated: deviceId={}, status={}", deviceId, status.getValue());
        }
    }

    @Transactional
    public void updateLastActive(String deviceId) {
        Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isPresent()) {
            Device device = deviceOpt.get();
            device.setLastActive(LocalDateTime.now());
            deviceRepository.save(device);
            logger.debug("Device last active time updated: deviceId={}", deviceId);
        }
    }

    public long getDeviceCount() {
        return deviceRepository.count();
    }

    public long getOnlineDeviceCount() {
        return deviceRepository.countByConnectionStatus(ConnectionStatus.ONLINE.getValue());
    }

    public long getOfflineDeviceCount() {
        return deviceRepository.countByConnectionStatus(ConnectionStatus.OFFLINE.getValue());
    }

    public List<String> getAllDeviceGroups() {
        return deviceRepository.findAllDeviceGroups();
    }

    private String generateDeviceId(String deviceType) {
        return deviceType + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String generateAuthToken(String deviceId) {
        return Jwts.builder()
                .setSubject(deviceId)
                .setIssuedAt(new java.util.Date())
                .setExpiration(new java.util.Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(SignatureAlgorithm.HS512, jwtSecret)
                .compact();
    }
}
