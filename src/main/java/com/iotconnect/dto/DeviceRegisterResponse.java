package com.iotconnect.dto;

public class DeviceRegisterResponse {

    private String deviceId;
    private String authToken;

    public DeviceRegisterResponse() {
    }

    public DeviceRegisterResponse(String deviceId, String authToken) {
        this.deviceId = deviceId;
        this.authToken = authToken;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }
}
