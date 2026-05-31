package com.edgescheduler.device.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edgescheduler.device.dto.DeviceActivateRequest;
import com.edgescheduler.device.dto.DeviceDTO;
import com.edgescheduler.device.entity.Device;

public interface DeviceService {

    DeviceDTO registerDevice(DeviceDTO deviceDTO);

    DeviceDTO activateDevice(DeviceActivateRequest request);

    DeviceDTO getDeviceByKey(String deviceKey);

    IPage<DeviceDTO> listDevices(Page<Device> page, String productKey, String status);

    DeviceDTO updateDevice(String deviceKey, DeviceDTO deviceDTO);

    boolean authenticateDevice(String deviceKey, String authSecret);

    DeviceDTO updateDeviceStatus(String deviceKey, String status);

    DeviceDTO deactivateDevice(String deviceKey);

    void deleteDevice(String deviceKey);

    void heartbeat(String deviceKey);

    DeviceDTO getDeviceStatus(String deviceKey);
}
