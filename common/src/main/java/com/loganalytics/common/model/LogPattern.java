package com.loganalytics.common.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class LogPattern {
    private String id;
    private String template;
    private List<String> variableSlots;
    private List<String> staticTokens;
    private LogLevel sampleLevel;
    private String sampleService;
    private Instant firstSeen;
    private Instant lastSeen;
    private AtomicLong totalCount;
    private Map<String, AtomicLong> countByService;
    private Map<String, AtomicLong> countByLevel;
    private boolean isNew;
    private double similarityThreshold;

    public LogPattern() {
        this.totalCount = new AtomicLong(0);
        this.countByService = new ConcurrentHashMap<>();
        this.countByLevel = new ConcurrentHashMap<>();
        this.variableSlots = new ArrayList<>();
        this.staticTokens = new ArrayList<>();
        this.firstSeen = Instant.now();
        this.lastSeen = Instant.now();
        this.isNew = true;
    }

    public LogPattern(String id, String template) {
        this();
        this.id = id;
        this.template = template;
    }

    public void incrementCount(String service, LogLevel level) {
        this.totalCount.incrementAndGet();
        this.countByService.computeIfAbsent(service, k -> new AtomicLong(0)).incrementAndGet();
        this.countByLevel.computeIfAbsent(level.name(), k -> new AtomicLong(0)).incrementAndGet();
        this.lastSeen = Instant.now();
        this.isNew = false;
    }

    public boolean matches(String message, double threshold) {
        String[] tokens1 = tokenize(template);
        String[] tokens2 = tokenize(message);

        if (tokens1.length != tokens2.length) return false;

        int matches = 0;
        int staticCount = 0;

        for (int i = 0; i < tokens1.length; i++) {
            if (isVariable(tokens1[i])) {
                continue;
            }
            staticCount++;
            if (tokens1[i].equals(tokens2[i])) {
                matches++;
            }
        }

        if (staticCount == 0) return false;
        return (double) matches / staticCount >= threshold;
    }

    private String[] tokenize(String s) {
        return s.split("\\s+");
    }

    private boolean isVariable(String token) {
        return token.startsWith("<") && token.endsWith(">");
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }

    public List<String> getVariableSlots() { return variableSlots; }
    public void setVariableSlots(List<String> variableSlots) { this.variableSlots = variableSlots; }

    public List<String> getStaticTokens() { return staticTokens; }
    public void setStaticTokens(List<String> staticTokens) { this.staticTokens = staticTokens; }

    public LogLevel getSampleLevel() { return sampleLevel; }
    public void setSampleLevel(LogLevel sampleLevel) { this.sampleLevel = sampleLevel; }

    public String getSampleService() { return sampleService; }
    public void setSampleService(String sampleService) { this.sampleService = sampleService; }

    public Instant getFirstSeen() { return firstSeen; }
    public void setFirstSeen(Instant firstSeen) { this.firstSeen = firstSeen; }

    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }

    public long getTotalCount() { return totalCount.get(); }
    public void setTotalCount(long count) { this.totalCount.set(count); }

    public Map<String, AtomicLong> getCountByService() { return countByService; }
    public Map<String, AtomicLong> getCountByLevel() { return countByLevel; }

    public boolean isNew() { return isNew; }
    public void setNew(boolean aNew) { isNew = aNew; }

    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
}
