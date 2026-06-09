package com.loganalytics.common.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LogEvent {
    private String id;
    private Instant timestamp;
    private Instant receivedAt;
    private LogLevel level;
    private String serviceName;
    private String hostname;
    private String sourceIp;
    private String traceId;
    private String spanId;
    private String message;
    private String rawMessage;
    private String patternId;
    private String patternTemplate;
    private Map<String, String> fields;
    private Map<String, String> tags;
    private String source;
    private String filePath;
    private long fileOffset;
    private int multiLineCount;
    private Map<String, Object> enrichedData;

    public LogEvent() {
        this.id = UUID.randomUUID().toString();
        this.receivedAt = Instant.now();
        this.fields = new HashMap<>();
        this.tags = new HashMap<>();
        this.enrichedData = new HashMap<>();
    }

    public LogEvent(LogEvent other) {
        this.id = other.id;
        this.timestamp = other.timestamp;
        this.receivedAt = other.receivedAt;
        this.level = other.level;
        this.serviceName = other.serviceName;
        this.hostname = other.hostname;
        this.sourceIp = other.sourceIp;
        this.traceId = other.traceId;
        this.spanId = other.spanId;
        this.message = other.message;
        this.rawMessage = other.rawMessage;
        this.patternId = other.patternId;
        this.patternTemplate = other.patternTemplate;
        this.fields = other.fields != null ? new HashMap<>(other.fields) : new HashMap<>();
        this.tags = other.tags != null ? new HashMap<>(other.tags) : new HashMap<>();
        this.source = other.source;
        this.filePath = other.filePath;
        this.fileOffset = other.fileOffset;
        this.multiLineCount = other.multiLineCount;
        this.enrichedData = other.enrichedData != null ? new HashMap<>(other.enrichedData) : new HashMap<>();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public LogLevel getLevel() { return level; }
    public void setLevel(LogLevel level) { this.level = level; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getRawMessage() { return rawMessage; }
    public void setRawMessage(String rawMessage) { this.rawMessage = rawMessage; }

    public String getPatternId() { return patternId; }
    public void setPatternId(String patternId) { this.patternId = patternId; }

    public String getPatternTemplate() { return patternTemplate; }
    public void setPatternTemplate(String patternTemplate) { this.patternTemplate = patternTemplate; }

    public Map<String, String> getFields() { return fields; }
    public void setFields(Map<String, String> fields) { this.fields = fields; }
    public void addField(String key, String value) { this.fields.put(key, value); }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public void addTag(String key, String value) { this.tags.put(key, value); }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public long getFileOffset() { return fileOffset; }
    public void setFileOffset(long fileOffset) { this.fileOffset = fileOffset; }

    public int getMultiLineCount() { return multiLineCount; }
    public void setMultiLineCount(int multiLineCount) { this.multiLineCount = multiLineCount; }

    public Map<String, Object> getEnrichedData() { return enrichedData; }
    public void setEnrichedData(Map<String, Object> enrichedData) { this.enrichedData = enrichedData; }
    public void addEnrichedData(String key, Object value) { this.enrichedData.put(key, value); }

    public String getPartitionKey() {
        return serviceName != null ? serviceName : (hostname != null ? hostname : "default");
    }
}
