package com.example.configmanager.device.dto;

import com.example.configmanager.device.enums.DeviceStatus;
import lombok.Data;

import java.io.Serializable;

@Data
public class DeviceQueryDTO implements Serializable {

    private String deviceType;

    private DeviceStatus status;

    private Integer pageNum = 1;

    private Integer pageSize = 10;
}
