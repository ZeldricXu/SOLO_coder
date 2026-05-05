package com.iotconnect.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;

public class ControlCommandRequest {

    @NotBlank(message = "设备ID不能为空")
    @Size(max = 64, message = "设备ID长度不能超过64")
    private String deviceId;

    @NotBlank(message = "指令类型不能为空")
    @Size(max = 64, message = "指令类型长度不能超过64")
    private String commandType;

    private Map<String, String> commandParams = new HashMap<>();

    private Integer timeoutSeconds;

    public ControlCommandRequest() {
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getCommandType() {
        return commandType;
    }

    public void setCommandType(String commandType) {
        this.commandType = commandType;
    }

    public Map<String, String> getCommandParams() {
        return commandParams;
    }

    public void setCommandParams(Map<String, String> commandParams) {
        this.commandParams = commandParams;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
