package com.projectservice.service;

import com.projectservice.model.environment.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class EnvironmentService {
    private final Map<String, Environment> envStore = new ConcurrentHashMap<>();
    private final Map<String, List<EnvironmentUsage>> usageStore = new ConcurrentHashMap<>();
    private final ReentrantLock usageLock = new ReentrantLock();
    private final AtomicInteger usageRecordCounter = new AtomicInteger(0);
    private final AtomicInteger reclaimCounter = new AtomicInteger(0);
    private boolean simulateCreationFailure = false;

    public EnvironmentService() {}

    public void setSimulateCreationFailure(boolean simulate) { this.simulateCreationFailure = simulate; }

    public Environment createEnvironment(CreateEnvironmentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request must not be null");
        }
        if (request.getName() == null || request.getName().isEmpty()) {
            throw new IllegalArgumentException("Name must not be empty");
        }
        if (request.getType() == null || request.getType().isEmpty()) {
            throw new IllegalArgumentException("Type must not be empty");
        }
        if (request.getOwner() == null || request.getOwner().isEmpty()) {
            throw new IllegalArgumentException("Owner must not be empty");
        }
        if (request.getProjectId() == null || request.getProjectId().isEmpty()) {
            throw new IllegalArgumentException("Project ID must not be empty");
        }

        for (Environment e : envStore.values()) {
            if (e.getName().equals(request.getName())) {
                throw new IllegalArgumentException("Environment name already exists: " + request.getName());
            }
        }

        if (simulateCreationFailure) {
            throw new RuntimeException("Simulated environment creation failure");
        }

        String envId = "env_" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now();

        Environment env = new Environment();
        env.setId(envId);
        env.setName(request.getName());
        env.setType(request.getType());
        env.setStatus("creating");
        env.setOwner(request.getOwner());
        env.setProjectId(request.getProjectId());
        env.setConfiguration(request.getConfiguration());
        env.setResources(request.getResources());
        env.setCreatedAt(now);
        env.setUpdatedAt(now);
        env.setLastActiveAt(now);

        if (request.getTtlHours() != null && request.getTtlHours() > 0) {
            Duration ttl = Duration.ofHours(request.getTtlHours());
            env.setTtl(ttl);
            env.setAutoReclaimAt(now.plus(ttl));
        }

        envStore.put(envId, env);

        env.setStatus("running");
        env.setUpdatedAt(LocalDateTime.now());

        recordUsage(envId, "creation", 1.0);

        return env;
    }

    public Environment getEnvironment(String envId) {
        if (envId == null || envId.isEmpty()) {
            throw new IllegalArgumentException("Environment ID must not be empty");
        }
        Environment env = envStore.get(envId);
        if (env == null) {
            throw new NoSuchElementException("Environment not found: " + envId);
        }
        return env;
    }

    public EnvironmentStatusResponse getEnvironmentStatus(String envId) {
        Environment env = getEnvironment(envId);
        return new EnvironmentStatusResponse(
            env.getId(), env.getName(), env.getType(), env.getStatus(),
            env.getOwner(), env.getAutoReclaimAt(), env.getCreatedAt(), env.getLastActiveAt()
        );
    }

    public List<Environment> listEnvironments(String owner, String projectId, String status, int page, int pageSize) {
        List<Environment> result = new ArrayList<>();
        for (Environment e : envStore.values()) {
            if (owner != null && !owner.isEmpty()) {
                if (!e.getOwner().equals(owner)) continue;
            }
            if (projectId != null && !projectId.isEmpty()) {
                if (!e.getProjectId().equals(projectId)) continue;
            }
            if (status != null && !status.isEmpty()) {
                if (!e.getStatus().equals(status)) continue;
            }
            result.add(e);
        }
        result.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, result.size());
        return start < result.size() ? result.subList(start, end) : new ArrayList<>();
    }

    public void updateEnvironmentStatus(String envId, String status) {
        Environment env = getEnvironment(envId);
        env.setStatus(status);
        env.setUpdatedAt(LocalDateTime.now());
        if ("running".equals(status)) {
            env.setLastActiveAt(LocalDateTime.now());
        }
    }

    public void deleteEnvironment(String envId) {
        Environment env = getEnvironment(envId);
        env.setStatus("deleting");
        env.setUpdatedAt(LocalDateTime.now());

        envStore.remove(envId);
        usageStore.remove(envId);
    }

    public List<String> reclaimExpiredEnvironments() {
        usageLock.lock();
        try {
            List<String> reclaimed = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            List<String> toReclaim = new ArrayList<>();

            for (Environment e : envStore.values()) {
                if ("running".equals(e.getStatus()) && e.getAutoReclaimAt() != null
                        && !now.isBefore(e.getAutoReclaimAt())) {
                    toReclaim.add(e.getId());
                }
            }

            for (String id : toReclaim) {
                envStore.remove(id);
                usageStore.remove(id);
                reclaimed.add(id);
                reclaimCounter.incrementAndGet();
            }

            return reclaimed;
        } finally {
            usageLock.unlock();
        }
    }

    public UsageStatisticsResponse getUsageStatistics(UsageStatisticsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request must not be null");
        }
        if (request.getEnvironmentId() == null || request.getEnvironmentId().isEmpty()) {
            throw new IllegalArgumentException("Environment ID must not be empty");
        }

        List<EnvironmentUsage> records = usageStore.getOrDefault(request.getEnvironmentId(), new ArrayList<>());
        List<EnvironmentUsage> filtered = new ArrayList<>();

        for (EnvironmentUsage r : records) {
            if (request.getResourceType() != null && !request.getResourceType().isEmpty()) {
                if (!r.getResourceType().equals(request.getResourceType())) continue;
            }
            if (request.getStartTime() != null) {
                if (r.getRecordedAt().isBefore(request.getStartTime())) continue;
            }
            if (request.getEndTime() != null) {
                if (r.getRecordedAt().isAfter(request.getEndTime())) continue;
            }
            filtered.add(r);
        }

        double total = 0;
        double peak = 0;
        for (EnvironmentUsage r : filtered) {
            total += r.getUsageValue();
            if (r.getUsageValue() > peak) peak = r.getUsageValue();
        }
        double average = filtered.isEmpty() ? 0.0 : total / filtered.size();

        return new UsageStatisticsResponse(
            request.getEnvironmentId(), request.getResourceType(),
            average, peak, filtered,
            filtered.isEmpty() ? null : filtered.get(0).getRecordedAt(),
            filtered.isEmpty() ? null : filtered.get(filtered.size() - 1).getRecordedAt()
        );
    }

    private void recordUsage(String envId, String resourceType, double value) {
        usageLock.lock();
        try {
            String usageId = "usage_" + usageRecordCounter.incrementAndGet();
            EnvironmentUsage usage = new EnvironmentUsage(usageId, envId, resourceType, value, LocalDateTime.now());
            usageStore.computeIfAbsent(envId, k -> new ArrayList<>()).add(usage);
        } finally {
            usageLock.unlock();
        }
    }

    public void recordPeriodicUsage(String envId, double cpu, double memory) {
        recordUsage(envId, "cpu", cpu);
        recordUsage(envId, "memory", memory);
    }

    public void extendTTL(String envId, int hours) {
        if (hours <= 0) {
            throw new IllegalArgumentException("Hours must be positive");
        }
        Environment env = getEnvironment(envId);
        if (env.getAutoReclaimAt() == null) {
            throw new IllegalStateException("Environment has no TTL set");
        }
        env.setAutoReclaimAt(env.getAutoReclaimAt().plusHours(hours));
        env.setUpdatedAt(LocalDateTime.now());
    }

    public int getUsageRecordCount() { return usageRecordCounter.get(); }
    public int getReclaimCount() { return reclaimCounter.get(); }
    public int getEnvStoreSize() { return envStore.size(); }
    public int getUsageStoreSize() { return usageStore.size(); }

    public static class CreateEnvironmentRequest {
        private String name;
        private String type;
        private String owner;
        private String projectId;
        private Map<String, Object> configuration;
        private Map<String, String> resources;
        private Integer ttlHours;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getOwner() { return owner; }
        public void setOwner(String owner) { this.owner = owner; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public Map<String, Object> getConfiguration() { return configuration; }
        public void setConfiguration(Map<String, Object> configuration) { this.configuration = configuration; }
        public Map<String, String> getResources() { return resources; }
        public void setResources(Map<String, String> resources) { this.resources = resources; }
        public Integer getTtlHours() { return ttlHours; }
        public void setTtlHours(Integer ttlHours) { this.ttlHours = ttlHours; }
    }

    public static class EnvironmentStatusResponse {
        private String id;
        private String name;
        private String type;
        private String status;
        private String owner;
        private LocalDateTime autoReclaimAt;
        private LocalDateTime createdAt;
        private LocalDateTime lastActiveAt;

        public EnvironmentStatusResponse() {}

        public EnvironmentStatusResponse(String id, String name, String type, String status,
                                         String owner, LocalDateTime autoReclaimAt,
                                         LocalDateTime createdAt, LocalDateTime lastActiveAt) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.status = status;
            this.owner = owner;
            this.autoReclaimAt = autoReclaimAt;
            this.createdAt = createdAt;
            this.lastActiveAt = lastActiveAt;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getType() { return type; }
        public String getStatus() { return status; }
        public String getOwner() { return owner; }
        public LocalDateTime getAutoReclaimAt() { return autoReclaimAt; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public LocalDateTime getLastActiveAt() { return lastActiveAt; }
    }

    public static class UsageStatisticsRequest {
        private String environmentId;
        private String resourceType;
        private LocalDateTime startTime;
        private LocalDateTime endTime;

        public String getEnvironmentId() { return environmentId; }
        public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
        public String getResourceType() { return resourceType; }
        public void setResourceType(String resourceType) { this.resourceType = resourceType; }
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    }

    public static class UsageStatisticsResponse {
        private String environmentId;
        private String resourceType;
        private double average;
        private double peak;
        private List<EnvironmentUsage> records;
        private LocalDateTime startTime;
        private LocalDateTime endTime;

        public UsageStatisticsResponse() {}

        public UsageStatisticsResponse(String environmentId, String resourceType,
                                       double average, double peak, List<EnvironmentUsage> records,
                                       LocalDateTime startTime, LocalDateTime endTime) {
            this.environmentId = environmentId;
            this.resourceType = resourceType;
            this.average = average;
            this.peak = peak;
            this.records = records;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        public String getEnvironmentId() { return environmentId; }
        public String getResourceType() { return resourceType; }
        public double getAverage() { return average; }
        public double getPeak() { return peak; }
        public List<EnvironmentUsage> getRecords() { return records; }
        public LocalDateTime getStartTime() { return startTime; }
        public LocalDateTime getEndTime() { return endTime; }
    }
}
