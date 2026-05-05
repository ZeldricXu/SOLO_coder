package com.iotconnect.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class DeviceRegisterRequest {

    @NotBlank(message = "设备名称不能为空")
    @Size(max = 128, message = "设备名称长度不能超过128")
    private String deviceName;

    @NotBlank(message = "设备类型不能为空")
    @Size(max = 64, message = "设备类型长度不能超过64")
    private String deviceType;

    @Size(max = 64, message = "设备分组长度不能超过64")
    private String deviceGroup;

    @NotBlank(message = "通信协议不能为空")
    @Size(max = 32, message = "通信协议长度不能超过32")
    private String protocol;

    public DeviceRegisterRequest() {
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getDeviceGroup() {
        return deviceGroup;
    }

    public void setDeviceGroup(String deviceGroup) {
        this.deviceGroup = deviceGroup;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }
}
