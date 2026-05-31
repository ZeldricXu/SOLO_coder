package com.device.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.device.platform.common.BusinessException;
import com.device.platform.common.DeviceStatus;
import com.device.platform.common.JsonUtils;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.DeviceActivateRequest;
import com.device.platform.dto.DeviceDeactivateRequest;
import com.device.platform.dto.DeviceHeartbeatRequest;
import com.device.platform.dto.DeviceStatusUpdateRequest;
import com.device.platform.entity.Device;
import com.device.platform.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceLifecycleService {

    private final DeviceMapper deviceMapper;

    @Transactional
    public Mono<Device> activateDevice(DeviceActivateRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            ctx.putAttribute("deviceId", request.getDeviceId());
            ctx.putAttribute("productKey", request.getProductKey());

            Device existing = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                    .eq(Device::getDeviceId, request.getDeviceId()));

            if (existing != null) {
                if (existing.getStatus() == DeviceStatus.DECOMMISSIONED) {
                    throw new BusinessException(400, "设备已注销，无法重新激活", ctx.getTraceId());
                }
                if (existing.getStatus() == DeviceStatus.ACTIVE || existing.getStatus() == DeviceStatus.ONLINE) {
                    return existing;
                }
            }

            String hashedSecret = hashSecret(request.getDeviceSecret());

            Device device = new Device();
            device.setDeviceId(request.getDeviceId());
            device.setDeviceName(request.getDeviceName() != null ? request.getDeviceName() : request.getDeviceId());
            device.setDeviceType(request.getDeviceType());
            device.setProductKey(request.getProductKey());
            device.setDeviceSecret(hashedSecret);
            device.setStatus(DeviceStatus.ACTIVE);
            device.setFirmwareVersion(request.getFirmwareVersion());
            device.setHardwareVersion(request.getHardwareVersion());
            device.setIpAddress(request.getIpAddress());
            device.setRegion(request.getRegion());
            device.setActivatedAt(Instant.now());
            device.setLastHeartbeatAt(Instant.now());

            if (request.getAttributes() != null) {
                device.setAttributes(JsonUtils.toJson(request.getAttributes()));
            }
            if (request.getTags() != null) {
                device.setTags(JsonUtils.toJson(request.getTags()));
            }

            if (existing != null) {
                device.setId(existing.getId());
                deviceMapper.updateById(device);
                log.info("设备重新激活成功: deviceId={}, traceId={}", request.getDeviceId(), ctx.getTraceId());
            } else {
                deviceMapper.insert(device);
                log.info("设备激活成功: deviceId={}, traceId={}", request.getDeviceId(), ctx.getTraceId());
            }

            return device;
        });
    }

    public Mono<Device> getDevice(String deviceId, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                    .eq(Device::getDeviceId, deviceId));

            if (device == null) {
                throw new BusinessException(404, "设备不存在: " + deviceId, ctx.getTraceId());
            }

            return device;
        });
    }

    @Transactional
    public Mono<Device> updateDeviceStatus(String deviceId, DeviceStatusUpdateRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                    .eq(Device::getDeviceId, deviceId));

            if (device == null) {
                throw new BusinessException(404, "设备不存在: " + deviceId, ctx.getTraceId());
            }

            if (request.getStatus() != null) {
                device.setStatus(request.getStatus());
            }
            if (request.getFirmwareVersion() != null) {
                device.setFirmwareVersion(request.getFirmwareVersion());
            }
            if (request.getIpAddress() != null) {
                device.setIpAddress(request.getIpAddress());
            }
            if (request.getLastHeartbeatAt() != null) {
                device.setLastHeartbeatAt(request.getLastHeartbeatAt());
            }

            deviceMapper.updateById(device);
            log.info("设备状态更新成功: deviceId={}, status={}, traceId={}",
                    deviceId, device.getStatus(), ctx.getTraceId());

            return device;
        });
    }

    @Transactional
    public Mono<Device> heartbeat(DeviceHeartbeatRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                    .eq(Device::getDeviceId, request.getDeviceId()));

            if (device == null) {
                throw new BusinessException(404, "设备不存在: " + request.getDeviceId(), ctx.getTraceId());
            }

            if (device.getStatus() == DeviceStatus.DECOMMISSIONED ||
                device.getStatus() == DeviceStatus.INACTIVE) {
                throw new BusinessException(400, "设备状态异常，无法处理心跳", ctx.getTraceId());
            }

            device.setLastHeartbeatAt(request.getTimestamp() != null ? request.getTimestamp() : Instant.now());
            device.setStatus(DeviceStatus.ONLINE);

            if (request.getFirmwareVersion() != null) {
                device.setFirmwareVersion(request.getFirmwareVersion());
            }

            deviceMapper.updateById(device);
            log.debug("设备心跳处理成功: deviceId={}, traceId={}", request.getDeviceId(), ctx.getTraceId());

            return device;
        });
    }

    @Transactional
    public Mono<Void> deactivateDevice(String deviceId, DeviceDeactivateRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                    .eq(Device::getDeviceId, deviceId));

            if (device == null) {
                throw new BusinessException(404, "设备不存在: " + deviceId, ctx.getTraceId());
            }

            device.setStatus(DeviceStatus.DECOMMISSIONED);
            device.setDeactivatedAt(Instant.now());
            device.setDeviceSecret(null);

            deviceMapper.updateById(device);
            log.info("设备注销成功: deviceId={}, reason={}, operator={}, traceId={}",
                    deviceId, request.getReason(), request.getOperator(), ctx.getTraceId());

            return null;
        });
    }

    private String hashSecret(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new BusinessException(500, "设备密钥加密失败");
        }
    }

    private String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
