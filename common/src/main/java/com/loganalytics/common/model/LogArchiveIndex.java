package com.loganalytics.common.model;

import java.time.Instant;

public class LogArchiveIndex {
    private String logId;
    private Instant timestamp;
    private String serviceName;
    private LogLevel level;
    private String patternId;
    private String traceId;
    private String bucketName;
    private String objectKey;
    private long byteOffset;
    private int byteLength;
    private String hostname;
    private String sourceIp;

    public LogArchiveIndex() {}

    public String getLogId() { return logId; }
    public void setLogId(String logId) { this.logId = logId; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public LogLevel getLevel() { return level; }
    public void setLevel(LogLevel level) { this.level = level; }

    public String getPatternId() { return patternId; }
    public void setPatternId(String patternId) { this.patternId = patternId; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public long getByteOffset() { return byteOffset; }
    public void setByteOffset(long byteOffset) { this.byteOffset = byteOffset; }

    public int getByteLength() { return byteLength; }
    public void setByteLength(int byteLength) { this.byteLength = byteLength; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }
}
