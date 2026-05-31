package com.edgescheduler.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.device.dto.DeviceActivateRequest;
import com.edgescheduler.device.dto.DeviceDTO;
import com.edgescheduler.device.entity.Device;
import com.edgescheduler.device.mapper.DeviceMapper;
import com.edgescheduler.device.service.DeviceService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceMapper deviceMapper;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public DeviceDTO registerDevice(DeviceDTO deviceDTO) {
        Device existing = deviceMapper.selectByDeviceKey(deviceDTO.getDeviceKey());
        if (existing != null) {
            throw BusinessException.conflict("Device already exists with key: " + deviceDTO.getDeviceKey());
        }

        Device device = new Device();
        BeanUtils.copyProperties(deviceDTO, device);
        device.setStatus(Device.Status.INACTIVE);
        if (device.getAuthType() == null) {
            device.setAuthType(Device.AuthType.TOKEN);
        }
        if (device.getAuthSecret() == null) {
            device.setAuthSecret(generateAuthSecret());
        }

        deviceMapper.insert(device);
        meterRegistry.counter("device.register.total").increment();
        log.info("Device registered: {}", device.getDeviceKey());

        return convertToDTO(device);
    }

    @Override
    @Transactional
    @CacheEvict(value = "device", key = "#request.deviceKey")
    public DeviceDTO activateDevice(DeviceActivateRequest request) {
        Device device = getDeviceEntityByKey(request.getDeviceKey());

        if (!device.getProductKey().equals(request.getProductKey())) {
            throw BusinessException.validationError("Product key mismatch");
        }

        if (Device.Status.ACTIVE.equals(device.getStatus()) || Device.Status.ONLINE.equals(device.getStatus())) {
            log.warn("Device already activated: {}", request.getDeviceKey());
            return convertToDTO(device);
        }

        if (device.getAuthSecret() != null && request.getAuthSecret() != null) {
            if (!device.getAuthSecret().equals(request.getAuthSecret())) {
                throw BusinessException.validationError("Invalid auth secret");
            }
        }

        device.setStatus(Device.Status.ONLINE);
        device.setFirmwareVersion(request.getFirmwareVersion());
        device.setMetadata(request.getMetadata());
        device.setActivatedAt(LocalDateTime.now());
        device.setLastOnlineAt(LocalDateTime.now());

        deviceMapper.updateById(device);
        meterRegistry.counter("device.activate.total").increment();
        log.info("Device activated: {}", request.getDeviceKey());

        return convertToDTO(device);
    }

    @Override
    @Cacheable(value = "device", key = "#deviceKey", unless = "#result == null")
    public DeviceDTO getDeviceByKey(String deviceKey) {
        Device device = getDeviceEntityByKey(deviceKey);
        return convertToDTO(device);
    }

    @Override
    public IPage<DeviceDTO> listDevices(Page<Device> page, String productKey, String status) {
        LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<>();
        if (productKey != null) {
            wrapper.eq(Device::getProductKey, productKey);
        }
        if (status != null) {
            wrapper.eq(Device::getStatus, status);
        }
        wrapper.orderByDesc(Device::getCreatedAt);

        return deviceMapper.selectPage(page, wrapper)
                .convert(this::convertToDTO);
    }

    @Override
    @Transactional
    @CacheEvict(value = "device", key = "#deviceKey")
    public DeviceDTO updateDevice(String deviceKey, DeviceDTO deviceDTO) {
        Device device = getDeviceEntityByKey(deviceKey);

        if (deviceDTO.getDeviceName() != null) {
            device.setDeviceName(deviceDTO.getDeviceName());
        }
        if (deviceDTO.getDeviceType() != null) {
            device.setDeviceType(deviceDTO.getDeviceType());
        }
        if (deviceDTO.getFirmwareVersion() != null) {
            device.setFirmwareVersion(deviceDTO.getFirmwareVersion());
        }
        if (deviceDTO.getMetadata() != null) {
            device.setMetadata(deviceDTO.getMetadata());
        }
        if (deviceDTO.getAuthSecret() != null) {
            device.setAuthSecret(deviceDTO.getAuthSecret());
        }

        deviceMapper.updateById(device);
        log.info("Device updated: {}", deviceKey);

        return convertToDTO(device);
    }

    @Override
    public boolean authenticateDevice(String deviceKey, String authSecret) {
        Device device = deviceMapper.selectByDeviceKey(deviceKey);
        if (device == null) {
            return false;
        }
        if (Device.Status.INACTIVE.equals(device.getStatus()) ||
            Device.Status.DEACTIVATED.equals(device.getStatus())) {
            return false;
        }
        return device.getAuthSecret() != null && device.getAuthSecret().equals(authSecret);
    }

    @Override
    @Transactional
    @CacheEvict(value = "device", key = "#deviceKey")
    public DeviceDTO updateDeviceStatus(String deviceKey, String status) {
        Device device = getDeviceEntityByKey(deviceKey);
        device.setStatus(status);
        if (Device.Status.ONLINE.equals(status)) {
            device.setLastOnlineAt(LocalDateTime.now());
        }
        deviceMapper.updateById(device);
        log.info("Device status updated: {} -> {}", deviceKey, status);
        return convertToDTO(device);
    }

    @Override
    @Transactional
    @CacheEvict(value = "device", key = "#deviceKey")
    public DeviceDTO deactivateDevice(String deviceKey) {
        Device device = getDeviceEntityByKey(deviceKey);
        device.setStatus(Device.Status.DEACTIVATED);
        deviceMapper.updateById(device);
        meterRegistry.counter("device.deactivate.total").increment();
        log.info("Device deactivated: {}", deviceKey);
        return convertToDTO(device);
    }

    @Override
    @Transactional
    @CacheEvict(value = "device", key = "#deviceKey")
    public void deleteDevice(String deviceKey) {
        Device device = getDeviceEntityByKey(deviceKey);
        deviceMapper.deleteById(device.getId());
        log.info("Device deleted: {}", deviceKey);
    }

    @Override
    public void heartbeat(String deviceKey) {
        int updated = deviceMapper.updateStatus(deviceKey, Device.Status.ONLINE, LocalDateTime.now());
        if (updated == 0) {
            log.warn("Heartbeat received for unknown device: {}", deviceKey);
        }
        meterRegistry.counter("device.heartbeat.total").increment();
    }

    @Override
    public DeviceDTO getDeviceStatus(String deviceKey) {
        Device device = getDeviceEntityByKey(deviceKey);
        DeviceDTO dto = new DeviceDTO();
        dto.setId(device.getId());
        dto.setDeviceKey(device.getDeviceKey());
        dto.setStatus(device.getStatus());
        dto.setLastOnlineAt(device.getLastOnlineAt());
        dto.setFirmwareVersion(device.getFirmwareVersion());
        return dto;
    }

    private Device getDeviceEntityByKey(String deviceKey) {
        Device device = deviceMapper.selectByDeviceKey(deviceKey);
        if (device == null) {
            throw BusinessException.notFound("Device not found: " + deviceKey);
        }
        return device;
    }

    private String generateAuthSecret() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private DeviceDTO convertToDTO(Device device) {
        DeviceDTO dto = new DeviceDTO();
        BeanUtils.copyProperties(device, dto);
        return dto;
    }
}
