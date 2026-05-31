package com.projectservice.model.environment;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Map;

public class Environment {
    private String id;
    private String name;
    private String type;
    private String status;
    private String owner;
    private String projectId;
    private Map<String, Object> configuration;
    private Duration ttl;
    private LocalDateTime autoReclaimAt;
    private Map<String, String> resources;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastActiveAt;

    public Environment() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Map<String, Object> getConfiguration() { return configuration; }
    public void setConfiguration(Map<String, Object> configuration) { this.configuration = configuration; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public LocalDateTime getAutoReclaimAt() { return autoReclaimAt; }
    public void setAutoReclaimAt(LocalDateTime autoReclaimAt) { this.autoReclaimAt = autoReclaimAt; }
    public Map<String, String> getResources() { return resources; }
    public void setResources(Map<String, String> resources) { this.resources = resources; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(LocalDateTime lastActiveAt) { this.lastActiveAt = lastActiveAt; }
}
