package com.loganalytics.test.builder;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LogEventBuilder {
    private String id;
    private Instant timestamp;
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

    public static LogEventBuilder aLogEvent() {
        return new LogEventBuilder();
    }

    private LogEventBuilder() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.level = LogLevel.INFO;
        this.serviceName = "default-service";
        this.hostname = "host-1";
        this.fields = new HashMap<>();
        this.tags = new HashMap<>();
        this.enrichedData = new HashMap<>();
    }

    public LogEventBuilder withId(String id) {
        this.id = id;
        return this;
    }

    public LogEventBuilder withTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public LogEventBuilder withTimestampSecondsAgo(long seconds) {
        this.timestamp = Instant.now().minusSeconds(seconds);
        return this;
    }

    public LogEventBuilder withLevel(LogLevel level) {
        this.level = level;
        return this;
    }

    public LogEventBuilder withLevelDebug() {
        return withLevel(LogLevel.DEBUG);
    }

    public LogEventBuilder withLevelInfo() {
        return withLevel(LogLevel.INFO);
    }

    public LogEventBuilder withLevelWarn() {
        return withLevel(LogLevel.WARN);
    }

    public LogEventBuilder withLevelError() {
        return withLevel(LogLevel.ERROR);
    }

    public LogEventBuilder withServiceName(String serviceName) {
        this.serviceName = serviceName;
        return this;
    }

    public LogEventBuilder withPaymentService() {
        return withServiceName("payment-service");
    }

    public LogEventBuilder withUserService() {
        return withServiceName("user-service");
    }

    public LogEventBuilder withOrderService() {
        return withServiceName("order-service");
    }

    public LogEventBuilder withGatewayService() {
        return withServiceName("gateway-service");
    }

    public LogEventBuilder withHostname(String hostname) {
        this.hostname = hostname;
        return this;
    }

    public LogEventBuilder withSourceIp(String sourceIp) {
        this.sourceIp = sourceIp;
        return this;
    }

    public LogEventBuilder withTraceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    public LogEventBuilder withSpanId(String spanId) {
        this.spanId = spanId;
        return this;
    }

    public LogEventBuilder withMessage(String message) {
        this.message = message;
        return this;
    }

    public LogEventBuilder withInfoMessage(String message) {
        return withLevelInfo().withMessage(message);
    }

    public LogEventBuilder withErrorMessage(String message) {
        return withLevelError().withMessage(message);
    }

    public LogEventBuilder withApacheCommonLog(String clientIp, String user, String timestamp,
                                               String method, String path, String protocol,
                                               int statusCode, int bytes) {
        this.rawMessage = String.format("%s - %s [%s] \"%s %s %s\" %d %d",
                clientIp, user, timestamp, method, path, protocol, statusCode, bytes);
        this.message = this.rawMessage;
        this.sourceIp = clientIp;
        return this;
    }

    public LogEventBuilder withUserLoginMessage(long userId, String ip) {
        return withMessage(String.format("User %d login from %s", userId, ip))
                .withField("userId", String.valueOf(userId))
                .withField("ip", ip);
    }

    public LogEventBuilder withUserLoginMessage(String userId, String ip) {
        return withMessage(String.format("User %s login from %s", userId, ip))
                .withField("userId", userId)
                .withField("ip", ip);
    }

    public LogEventBuilder withConnectionTimeoutMessage(String host, int port) {
        return withErrorMessage(String.format("Connection timeout after 30s to %s:%d", host, port))
                .withField("host", host)
                .withField("port", String.valueOf(port));
    }

    public LogEventBuilder withConnectionTimeoutMessage(String host, int port, int timeoutSeconds) {
        return withErrorMessage(String.format("Connection timeout after %ds to %s:%d", timeoutSeconds, host, port))
                .withField("host", host)
                .withField("port", String.valueOf(port))
                .withField("timeout", String.valueOf(timeoutSeconds));
    }

    public LogEventBuilder withRawMessage(String rawMessage) {
        this.rawMessage = rawMessage;
        return this;
    }

    public LogEventBuilder withPatternId(String patternId) {
        this.patternId = patternId;
        return this;
    }

    public LogEventBuilder withPatternTemplate(String patternTemplate) {
        this.patternTemplate = patternTemplate;
        return this;
    }

    public LogEventBuilder withField(String key, String value) {
        this.fields.put(key, value);
        return this;
    }

    public LogEventBuilder withFields(Map<String, String> fields) {
        this.fields = fields;
        return this;
    }

    public LogEventBuilder withTag(String key, String value) {
        this.tags.put(key, value);
        return this;
    }

    public LogEventBuilder withTags(Map<String, String> tags) {
        this.tags = tags;
        return this;
    }

    public LogEventBuilder withSource(String source) {
        this.source = source;
        return this;
    }

    public LogEventBuilder withSourceFile() {
        return withSource("FILE");
    }

    public LogEventBuilder withSourceStdout() {
        return withSource("STDOUT");
    }

    public LogEventBuilder withSourceSocket() {
        return withSource("SOCKET");
    }

    public LogEventBuilder withFilePath(String filePath) {
        this.filePath = filePath;
        return this;
    }

    public LogEventBuilder withFileOffset(long fileOffset) {
        this.fileOffset = fileOffset;
        return this;
    }

    public LogEventBuilder withMultiLineCount(int multiLineCount) {
        this.multiLineCount = multiLineCount;
        return this;
    }

    public LogEventBuilder withEnrichedData(String key, Object value) {
        this.enrichedData.put(key, value);
        return this;
    }

    public LogEventBuilder withEnrichedTeam(String team) {
        return withEnrichedData("team", team);
    }

    public LogEventBuilder withEnrichedGeo(String country, String city) {
        return withEnrichedData("country", country)
                .withEnrichedData("city", city);
    }

    public LogEvent build() {
        LogEvent event = new LogEvent();
        event.setId(id);
        event.setTimestamp(timestamp);
        event.setLevel(level);
        event.setServiceName(serviceName);
        event.setHostname(hostname);
        event.setSourceIp(sourceIp);
        event.setTraceId(traceId);
        event.setSpanId(spanId);
        event.setMessage(message);
        event.setRawMessage(rawMessage);
        event.setPatternId(patternId);
        event.setPatternTemplate(patternTemplate);
        event.setFields(fields);
        event.setTags(tags);
        event.setSource(source);
        event.setFilePath(filePath);
        event.setFileOffset(fileOffset);
        event.setMultiLineCount(multiLineCount);
        event.setEnrichedData(enrichedData);
        return event;
    }

    public LogEvent buildCopy() {
        return new LogEvent(build());
    }
}
