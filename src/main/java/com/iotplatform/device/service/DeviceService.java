package com.iotplatform.device.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iotplatform.device.dto.DeviceAuthDTO;
import com.iotplatform.device.dto.DeviceHeartbeatDTO;
import com.iotplatform.device.dto.DeviceRegisterDTO;
import com.iotplatform.device.entity.SysDevice;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.Optional;

public interface DeviceService {

    Mono<SysDevice> registerDevice(DeviceRegisterDTO dto);

    Mono<SysDevice> getDevice(String deviceId);

    Mono<Optional<SysDevice>> findDevice(String deviceId);

    Mono<IPage<SysDevice>> listDevices(String deviceType, String status, String keyword,
                                       Integer pageNum, Integer pageSize);

    Mono<Boolean> authenticateDevice(DeviceAuthDTO dto);

    Mono<Void> heartbeat(DeviceHeartbeatDTO dto);

    Mono<SysDevice> activateDevice(String deviceId);

    Mono<Void> deactivateDevice(String deviceId);

    Mono<Void> deleteDevice(String deviceId);

    Mono<Map<String, Long>> getDeviceStats();

    Mono<SysDevice> updateDeviceMetadata(String deviceId, Map<String, Object> metadata);
}
