package com.example.configmanager.device.dto;

import com.example.configmanager.device.enums.DeviceStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class DeviceStatusUpdateDTO implements Serializable {

    @NotNull(message = "设备状态不能为空")
    private DeviceStatus status;
}
