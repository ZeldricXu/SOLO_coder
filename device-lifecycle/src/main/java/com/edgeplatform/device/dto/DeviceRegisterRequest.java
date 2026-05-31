package com.edgeplatform.device.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

@Data
public class DeviceRegisterRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String deviceName;
    private String deviceType;
    private String firmwareVersion;
    private String hardwareVersion;
    private String ipAddress;
    private String location;
    private Map<String, Object> metadata;
    private Map<String, Object> capabilities;
}
