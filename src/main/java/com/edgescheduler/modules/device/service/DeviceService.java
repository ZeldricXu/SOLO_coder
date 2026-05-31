package com.edgescheduler.modules.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.common.exception.ValidationException;
import com.edgescheduler.common.util.IdGenerator;
import com.edgescheduler.common.util.SignatureUtil;
import com.edgescheduler.domain.enums.DeviceStatus;
import com.edgescheduler.modules.device.domain.DeviceInfo;
import com.edgescheduler.modules.device.mapper.DeviceInfoMapper;
import com.edgescheduler.modules.shadow.service.DeviceShadowService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceInfoMapper deviceInfoMapper;
    private final DeviceShadowService deviceShadowService;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    @Transactional(rollbackFor = Exception.class)
    public Mono<DeviceInfo> registerDevice(DeviceInfo deviceInfo, String signature, long timestamp) {
        validateRegistration(deviceInfo, signature, timestamp);

        DeviceInfo existing = deviceInfoMapper.selectOne(
                new LambdaQueryWrapper<DeviceInfo>()
                        .eq(DeviceInfo::getSerialNumber, deviceInfo.getSerialNumber()));
        if (existing != null) {
            return Mono.error(new BusinessException("设备已注册"));
        }

        String deviceId = IdGenerator.generateDeviceId();
        deviceInfo.setDeviceId(deviceId);
        deviceInfo.setStatus(DeviceStatus.INACTIVE);
        deviceInfo.setDeviceSecret(generateDeviceSecret());
        deviceInfo.setHeartbeatInterval(deviceInfo.getHeartbeatInterval() != null ?
                deviceInfo.getHeartbeatInterval() : 60);

        deviceInfoMapper.insert(deviceInfo);

        deviceShadowService.createShadow(deviceId).subscribe();

        updateMetrics("device_registered");
        return Mono.just(deviceInfo);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<DeviceInfo> activateDevice(String deviceId, String deviceSecret) {
        DeviceInfo device = getDevice(deviceId);

        if (!device.getDeviceSecret().equals(deviceSecret)) {
            throw new ValidationException("设备密钥验证失败");
        }

        device.setStatus(DeviceStatus.ACTIVE);
        device.setActivatedAt(LocalDateTime.now());
        device.setLastOnlineTime(LocalDateTime.now());
        device.setLastHeartbeatTime(LocalDateTime.now());

        deviceInfoMapper.updateById(device);

        redisTemplate.opsForValue().set("device:online:" + deviceId, "true",
                Duration.ofSeconds(device.getHeartbeatInterval() * 3)).subscribe();

        updateMetrics("device_activated");
        return Mono.just(device);
    }

    public Mono<DeviceInfo> authenticateDevice(String deviceId, String credentials) {
        DeviceInfo device = getDevice(deviceId);

        boolean authenticated = switch (device.getAuthMethod()) {
            case "SECRET" -> device.getDeviceSecret().equals(credentials);
            case "CERT" -> validateCertificate(device.getDeviceCert(), credentials);
            case "TOKEN" -> validateToken(deviceId, credentials);
            default -> false;
        };

        if (!authenticated) {
            throw new ValidationException("设备认证失败");
        }

        return Mono.just(device);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<DeviceInfo> heartbeat(String deviceId, String ipAddress) {
        DeviceInfo device = getDevice(deviceId);

        if (device.getStatus() != DeviceStatus.ACTIVE) {
            throw new BusinessException("设备未激活");
        }

        device.setLastHeartbeatTime(LocalDateTime.now());
        device.setLastOnlineTime(LocalDateTime.now());
        if (ipAddress != null) {
            device.setIpAddress(ipAddress);
        }

        if (device.getStatus() == DeviceStatus.OFFLINE) {
            device.setStatus(DeviceStatus.ACTIVE);
            updateMetrics("device_came_online");
        }

        deviceInfoMapper.updateById(device);

        redisTemplate.opsForValue().set("device:online:" + deviceId, "true",
                Duration.ofSeconds(device.getHeartbeatInterval() * 3)).subscribe();

        return Mono.just(device);
    }

    public Mono<DeviceInfo> getDevice(String deviceId) {
        DeviceInfo device = deviceInfoMapper.selectOne(
                new LambdaQueryWrapper<DeviceInfo>().eq(DeviceInfo::getDeviceId, deviceId));
        if (device == null) {
            return Mono.error(new BusinessException("设备不存在"));
        }
        return Mono.just(device);
    }

    public Flux<DeviceInfo> getDevices(DeviceStatus status, String deviceType) {
        List<DeviceInfo> devices = deviceInfoMapper.selectList(
                new LambdaQueryWrapper<DeviceInfo>()
                        .eq(status != null, DeviceInfo::getStatus, status)
                        .eq(deviceType != null, DeviceInfo::getDeviceType, deviceType)
                        .orderByDesc(DeviceInfo::getCreatedAt));
        return Flux.fromIterable(devices);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<DeviceInfo> updateDeviceStatus(String deviceId, DeviceStatus status) {
        DeviceInfo device = getDevice(deviceId);
        device.setStatus(status);
        deviceInfoMapper.updateById(device);

        if (status == DeviceStatus.OFFLINE || status == DeviceStatus.INACTIVE) {
            redisTemplate.delete("device:online:" + deviceId).subscribe();
        }

        updateMetrics("device_status_updated");
        return Mono.just(device);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> deactivateDevice(String deviceId) {
        DeviceInfo device = getDevice(deviceId);
        device.setStatus(DeviceStatus.INACTIVE);
        deviceInfoMapper.updateById(device);

        redisTemplate.delete("device:online:" + deviceId).subscribe();
        deviceShadowService.deleteShadow(deviceId).subscribe();

        updateMetrics("device_deactivated");
        return Mono.empty();
    }

    @Scheduled(fixedDelay = 30000)
    @Transactional(rollbackFor = Exception.class)
    public void checkDeviceHeartbeats() {
        List<DeviceInfo> activeDevices = deviceInfoMapper.selectList(
                new LambdaQueryWrapper<DeviceInfo>()
                        .in(DeviceInfo::getStatus, DeviceStatus.ACTIVE, DeviceStatus.UPGRADING));

        LocalDateTime now = LocalDateTime.now();
        for (DeviceInfo device : activeDevices) {
            if (device.getLastHeartbeatTime() != null) {
                long secondsSinceLastHeartbeat =
                        java.time.Duration.between(device.getLastHeartbeatTime(), now).getSeconds();
                if (secondsSinceLastHeartbeat > device.getHeartbeatInterval() * 3) {
                    device.setStatus(DeviceStatus.OFFLINE);
                    deviceInfoMapper.updateById(device);
                    redisTemplate.delete("device:online:" + device.getDeviceId()).subscribe();
                    log.warn("Device {} marked as offline due to heartbeat timeout", device.getDeviceId());
                    updateMetrics("device_went_offline");
                }
            }
        }
    }

    public Mono<Map<String, Object>> getDeviceStats() {
        Map<String, Object> stats = new HashMap<>();

        long totalDevices = deviceInfoMapper.selectCount(null);
        stats.put("totalDevices", totalDevices);

        for (DeviceStatus status : DeviceStatus.values()) {
            long count = deviceInfoMapper.selectCount(
                    new LambdaQueryWrapper<DeviceInfo>().eq(DeviceInfo::getStatus, status));
            stats.put(status.name().toLowerCase() + "Devices", count);
        }

        return Mono.just(stats);
    }

    public Mono<Boolean> isDeviceOnline(String deviceId) {
        return redisTemplate.hasKey("device:online:" + deviceId)
                .map(Boolean::booleanValue);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<DeviceInfo> updateDeviceConfig(String deviceId, Map<String, Object> config) {
        DeviceInfo device = getDevice(deviceId);
        device.setDeviceConfig(config);
        deviceInfoMapper.updateById(device);
        return Mono.just(device);
    }

    private void validateRegistration(DeviceInfo deviceInfo, String signature, long timestamp) {
        if (deviceInfo.getSerialNumber() == null || deviceInfo.getSerialNumber().isEmpty()) {
            throw new ValidationException("设备序列号不能为空");
        }
        if (deviceInfo.getDeviceModel() == null || deviceInfo.getDeviceModel().isEmpty()) {
            throw new ValidationException("设备型号不能为空");
        }

        if (!SignatureUtil.validateTimestamp(timestamp, 300)) {
            throw new ValidationException("请求已过期");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("serialNumber", deviceInfo.getSerialNumber());
        params.put("deviceModel", deviceInfo.getDeviceModel());
        params.put("timestamp", timestamp);

        if (!SignatureUtil.validateSignature(params, signature)) {
            throw new ValidationException("签名验证失败");
        }
    }

    private String generateDeviceSecret() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private boolean validateCertificate(String deviceCert, String credentials) {
        return deviceCert != null && deviceCert.equals(credentials);
    }

    private boolean validateToken(String deviceId, String token) {
        try {
            String expectedToken = SignatureUtil.hmacSign(deviceId + ":" + System.currentTimeMillis() / 3600000);
            return expectedToken.equals(token);
        } catch (Exception e) {
            return false;
        }
    }

    private void updateMetrics(String action) {
        meterRegistry.counter("edge_scheduler_device_operations_total", "action", action).increment();
    }
}
