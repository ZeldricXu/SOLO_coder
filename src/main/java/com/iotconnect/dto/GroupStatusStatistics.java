package com.iotconnect.dto;

public class GroupStatusStatistics {

    private String deviceGroup;
    private long totalDevices;
    private long onlineDevices;
    private long offlineDevices;
    private double onlineRate;

    public GroupStatusStatistics() {
    }

    public String getDeviceGroup() {
        return deviceGroup;
    }

    public void setDeviceGroup(String deviceGroup) {
        this.deviceGroup = deviceGroup;
    }

    public long getTotalDevices() {
        return totalDevices;
    }

    public void setTotalDevices(long totalDevices) {
        this.totalDevices = totalDevices;
    }

    public long getOnlineDevices() {
        return onlineDevices;
    }

    public void setOnlineDevices(long onlineDevices) {
        this.onlineDevices = onlineDevices;
    }

    public long getOfflineDevices() {
        return offlineDevices;
    }

    public void setOfflineDevices(long offlineDevices) {
        this.offlineDevices = offlineDevices;
    }

    public double getOnlineRate() {
        return onlineRate;
    }

    public void setOnlineRate(double onlineRate) {
        this.onlineRate = onlineRate;
    }
}
