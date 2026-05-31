package com.parking.platform.environment.entity;

import com.parking.platform.common.entity.BaseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class PreviewEnvironment extends BaseEntity {

    private String name;
    private String branch;
    private String commit;
    private String status;
    private String owner;
    private String namespace;
    private Map<String, String> endpoints;
    private Map<String, Object> config;
    private Instant createdAt;
    private Instant startedAt;
    private Instant expiresAt;
    private Instant lastActivityAt;
    private Long usageMinutes;
    private String template;
    private Map<String, String> labels;

    public PreviewEnvironment() {
        super();
        this.status = "CREATING";
        this.endpoints = new HashMap<>();
        this.config = new HashMap<>();
        this.labels = new HashMap<>();
        this.createdAt = Instant.now();
        this.lastActivityAt = Instant.now();
        this.usageMinutes = 0L;
    }

    @Override
    protected String getIdPrefix() { return "env"; }

    public void start() {
        this.status = "RUNNING";
        this.startedAt = Instant.now();
        this.lastActivityAt = Instant.now();
    }

    public void stop() {
        this.status = "STOPPED";
    }

    public void destroy() {
        this.status = "DESTROYED";
    }

    public void extendTtl(long minutes) {
        if (this.expiresAt == null) {
            this.expiresAt = Instant.now();
        }
        this.expiresAt = this.expiresAt.plusSeconds(minutes * 60);
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isIdle(long idleMinutes) {
        return lastActivityAt != null && 
               java.time.Duration.between(lastActivityAt, Instant.now()).toMinutes() > idleMinutes;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("name", name);
        map.put("branch", branch);
        map.put("commit", commit);
        map.put("status", status);
        map.put("owner", owner);
        map.put("namespace", namespace);
        map.put("endpoints", endpoints);
        map.put("config", config);
        map.put("createdAt", createdAt);
        map.put("startedAt", startedAt);
        map.put("expiresAt", expiresAt);
        map.put("lastActivityAt", lastActivityAt);
        map.put("usageMinutes", usageMinutes);
        map.put("template", template);
        map.put("labels", labels);
        return map;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getCommit() { return commit; }
    public void setCommit(String commit) { this.commit = commit; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public Map<String, String> getEndpoints() { return endpoints; }
    public void setEndpoints(Map<String, String> endpoints) { this.endpoints = endpoints; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(Instant lastActivityAt) { this.lastActivityAt = lastActivityAt; }
    public Long getUsageMinutes() { return usageMinutes; }
    public void setUsageMinutes(Long usageMinutes) { this.usageMinutes = usageMinutes; }
    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }
    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }
}
