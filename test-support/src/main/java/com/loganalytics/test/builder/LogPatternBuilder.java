package com.loganalytics.test.builder;

import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.model.LogPattern;
import com.loganalytics.common.util.IdUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class LogPatternBuilder {
    private String id;
    private String template;
    private List<String> variableSlots;
    private List<String> staticTokens;
    private LogLevel sampleLevel;
    private String sampleService;
    private Instant firstSeen;
    private Instant lastSeen;
    private long totalCount;
    private boolean isNew;
    private double similarityThreshold;

    public static LogPatternBuilder aLogPattern() {
        return new LogPatternBuilder();
    }

    private LogPatternBuilder() {
        this.id = IdUtils.generateId("pattern");
        this.variableSlots = new ArrayList<>();
        this.staticTokens = new ArrayList<>();
        this.sampleLevel = LogLevel.INFO;
        this.sampleService = "default-service";
        this.firstSeen = Instant.now();
        this.lastSeen = Instant.now();
        this.isNew = true;
        this.similarityThreshold = 0.8;
    }

    public LogPatternBuilder withId(String id) {
        this.id = id;
        return this;
    }

    public LogPatternBuilder withTemplate(String template) {
        this.template = template;
        parseTemplate(template);
        return this;
    }

    public LogPatternBuilder withUserLoginTemplate() {
        return withTemplate("User <id> login from <ip>");
    }

    public LogPatternBuilder withConnectionTimeoutTemplate() {
        return withTemplate("Connection timeout after <seconds>s to <host>:<port>");
    }

    public LogPatternBuilder withDbQueryFailedTemplate() {
        return withTemplate("Database query failed: <exception> at line <line>");
    }

    public LogPatternBuilder withRequestProcessedTemplate() {
        return withTemplate("Request processed successfully in <duration>ms");
    }

    public LogPatternBuilder withVariableSlot(String slot) {
        this.variableSlots.add(slot);
        return this;
    }

    public LogPatternBuilder withStaticToken(String token) {
        this.staticTokens.add(token);
        return this;
    }

    public LogPatternBuilder withSampleLevel(LogLevel level) {
        this.sampleLevel = level;
        return this;
    }

    public LogPatternBuilder withSampleService(String service) {
        this.sampleService = service;
        return this;
    }

    public LogPatternBuilder withPaymentService() {
        return withSampleService("payment-service");
    }

    public LogPatternBuilder withGatewayService() {
        return withSampleService("gateway-service");
    }

    public LogPatternBuilder withLevelInfo() {
        return withSampleLevel(LogLevel.INFO);
    }

    public LogPatternBuilder withLevelError() {
        return withSampleLevel(LogLevel.ERROR);
    }

    public LogPatternBuilder withLevelWarn() {
        return withSampleLevel(LogLevel.WARN);
    }

    public LogPatternBuilder withLevelDebug() {
        return withSampleLevel(LogLevel.DEBUG);
    }

    public LogPatternBuilder withFirstSeen(Instant firstSeen) {
        this.firstSeen = firstSeen;
        return this;
    }

    public LogPatternBuilder withFirstSeenMinutesAgo(long minutes) {
        this.firstSeen = Instant.now().minusSeconds(minutes * 60);
        return this;
    }

    public LogPatternBuilder withLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
        return this;
    }

    public LogPatternBuilder withTotalCount(long totalCount) {
        this.totalCount = totalCount;
        return this;
    }

    public LogPatternBuilder withHighFrequency() {
        return withTotalCount(10000);
    }

    public LogPatternBuilder withLowFrequency() {
        return withTotalCount(10);
    }

    public LogPatternBuilder asNew() {
        this.isNew = true;
        return this;
    }

    public LogPatternBuilder asExisting() {
        this.isNew = false;
        return this;
    }

    public LogPatternBuilder withSimilarityThreshold(double threshold) {
        this.similarityThreshold = threshold;
        return this;
    }

    private void parseTemplate(String template) {
        String[] tokens = template.split("\\s+");
        for (String token : tokens) {
            if (token.startsWith("<") && token.endsWith(">")) {
                variableSlots.add(token.substring(1, token.length() - 1));
            } else {
                staticTokens.add(token);
            }
        }
    }

    public LogPattern build() {
        LogPattern pattern = new LogPattern(id, template);
        pattern.setVariableSlots(new ArrayList<>(variableSlots));
        pattern.setStaticTokens(new ArrayList<>(staticTokens));
        pattern.setSampleLevel(sampleLevel);
        pattern.setSampleService(sampleService);
        pattern.setFirstSeen(firstSeen);
        pattern.setLastSeen(lastSeen);
        pattern.setTotalCount(totalCount);
        pattern.setNew(isNew);
        pattern.setSimilarityThreshold(similarityThreshold);
        return pattern;
    }
}
