package com.parking.platform.common.entity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class DeviceShadowEntity extends BaseEntity {

    private String deviceId;
    private Map<String, Object> desired;
    private Map<String, Object> reported;
    private Integer desiredVersion;
    private Integer reportedVersion;
    private String status;
    private Instant lastSyncAt;

    public DeviceShadowEntity() {
        super();
        this.desired = new HashMap<>();
        this.reported = new HashMap<>();
        this.desiredVersion = 1;
        this.reportedVersion = 1;
        this.status = "idle";
    }

    public DeviceShadowEntity(String deviceId) {
        this();
        this.deviceId = deviceId;
    }

    @Override
    protected String getIdPrefix() {
        return "shadow";
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public Map<String, Object> getDesired() {
        return desired;
    }

    public void setDesired(Map<String, Object> desired) {
        this.desired = desired;
    }

    public Map<String, Object> getReported() {
        return reported;
    }

    public void setReported(Map<String, Object> reported) {
        this.reported = reported;
    }

    public Integer getDesiredVersion() {
        return desiredVersion;
    }

    public void setDesiredVersion(Integer desiredVersion) {
        this.desiredVersion = desiredVersion;
    }

    public Integer getReportedVersion() {
        return reportedVersion;
    }

    public void setReportedVersion(Integer reportedVersion) {
        this.reportedVersion = reportedVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }

    public void setLastSyncAt(Instant lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }

    public void updateDesired(Map<String, Object> updates) {
        if (updates != null && !updates.isEmpty()) {
            this.desired.putAll(updates);
            this.desiredVersion++;
            this.status = "pending_sync";
        }
    }

    public void updateReported(Map<String, Object> updates) {
        if (updates != null && !updates.isEmpty()) {
            this.reported.putAll(updates);
            this.reportedVersion++;
        }
    }

    public boolean isSynced() {
        return desired.equals(reported) || desired.isEmpty();
    }

    public void markSynced() {
        this.status = "synced";
        this.lastSyncAt = Instant.now();
        this.reported.putAll(this.desired);
        this.reportedVersion++;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("shadow_id", getId());
        map.put("device_id", deviceId);
        map.put("desired", desired);
        map.put("reported", reported);
        map.put("desired_version", desiredVersion);
        map.put("reported_version", reportedVersion);
        map.put("status", status);
        map.put("last_sync_at", lastSyncAt);
        return map;
    }
}
