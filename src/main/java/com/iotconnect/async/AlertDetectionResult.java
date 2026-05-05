package com.iotconnect.async;

public class AlertDetectionResult {

    private final String deviceId;
    private final String metric;
    private final boolean success;
    private final long durationMs;
    private final String errorMessage;
    private final long timestamp;

    public AlertDetectionResult(String deviceId, String metric, boolean success, 
                                 long durationMs, String errorMessage) {
        this.deviceId = deviceId;
        this.metric = metric;
        this.success = success;
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
        this.timestamp = System.currentTimeMillis();
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getMetric() {
        return metric;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "AlertDetectionResult{" +
                "deviceId='" + deviceId + '\'' +
                ", metric='" + metric + '\'' +
                ", success=" + success +
                ", durationMs=" + durationMs +
                ", errorMessage='" + errorMessage + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
