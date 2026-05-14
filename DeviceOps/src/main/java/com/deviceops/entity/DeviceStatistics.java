package com.deviceops.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "device_statistics")
public class DeviceStatistics {

    @Id
    @Column(name = "stat_id")
    private String statId;

    @Column(name = "stat_month", nullable = false, unique = true)
    private String statMonth;

    @Column(name = "device_count", nullable = false)
    private Integer deviceCount;

    @Column(name = "fault_count", nullable = false)
    private Integer faultCount;

    @Column(name = "task_count", nullable = false)
    private Integer taskCount;

    @Column(name = "avg_response_time", nullable = false)
    private Double avgResponseTime;

    public DeviceStatistics() {
    }

    public String getStatId() {
        return statId;
    }

    public void setStatId(String statId) {
        this.statId = statId;
    }

    public String getStatMonth() {
        return statMonth;
    }

    public void setStatMonth(String statMonth) {
        this.statMonth = statMonth;
    }

    public Integer getDeviceCount() {
        return deviceCount;
    }

    public void setDeviceCount(Integer deviceCount) {
        this.deviceCount = deviceCount;
    }

    public Integer getFaultCount() {
        return faultCount;
    }

    public void setFaultCount(Integer faultCount) {
        this.faultCount = faultCount;
    }

    public Integer getTaskCount() {
        return taskCount;
    }

    public void setTaskCount(Integer taskCount) {
        this.taskCount = taskCount;
    }

    public Double getAvgResponseTime() {
        return avgResponseTime;
    }

    public void setAvgResponseTime(Double avgResponseTime) {
        this.avgResponseTime = avgResponseTime;
    }
}
