package com.parking.platform.environment.service;

import com.parking.platform.environment.entity.PreviewEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class EnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentService.class);

    private final Map<String, PreviewEnvironment> envStore = new ConcurrentHashMap<>();

    public PreviewEnvironment create(PreviewEnvironment env) {
        env.setStatus("CREATING");
        env.setCreatedAt(Instant.now());
        env.setLastActivityAt(Instant.now());
        envStore.put(env.getId(), env);

        log.info("Creating environment: {} for branch: {}", env.getName(), env.getBranch());

        try {
            Thread.sleep(2000);
            env.start();
            env.getEndpoints().put("web", "http://" + env.getName() + ".preview.local");
            env.getEndpoints().put("api", "http://api." + env.getName() + ".preview.local");
            log.info("Environment created successfully: {}", env.getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            env.setStatus("FAILED");
        }

        return env;
    }

    public PreviewEnvironment get(String id) {
        return envStore.get(id);
    }

    public List<PreviewEnvironment> list(String owner, String status, String template) {
        return envStore.values().stream()
                .filter(e -> owner == null || owner.equals(e.getOwner()))
                .filter(e -> status == null || status.equals(e.getStatus()))
                .filter(e -> template == null || template.equals(e.getTemplate()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .collect(Collectors.toList());
    }

    public PreviewEnvironment start(String id) {
        PreviewEnvironment env = get(id);
        if (env != null) {
            env.start();
            log.info("Environment started: {}", id);
        }
        return env;
    }

    public PreviewEnvironment stop(String id) {
        PreviewEnvironment env = get(id);
        if (env != null) {
            env.stop();
            log.info("Environment stopped: {}", id);
        }
        return env;
    }

    public boolean delete(String id) {
        PreviewEnvironment env = envStore.remove(id);
        if (env != null) {
            env.destroy();
            log.info("Environment deleted: {}", id);
            return true;
        }
        return false;
    }

    public PreviewEnvironment extend(String id, long minutes) {
        PreviewEnvironment env = get(id);
        if (env != null) {
            env.extendTtl(minutes);
            log.info("Environment TTL extended: {} by {} minutes", id, minutes);
        }
        return env;
    }

    public PreviewEnvironment heartbeat(String id) {
        PreviewEnvironment env = get(id);
        if (env != null) {
            env.setLastActivityAt(Instant.now());
            env.setUsageMinutes(env.getUsageMinutes() + 1);
        }
        return env;
    }

    @Scheduled(fixedRate = 300000)
    public void cleanupExpiredEnvironments() {
        log.info("Running environment cleanup job...");

        for (Map.Entry<String, PreviewEnvironment> entry : envStore.entrySet()) {
            PreviewEnvironment env = entry.getValue();

            if (env.isExpired()) {
                log.info("Removing expired environment: {}", env.getId());
                envStore.remove(env.getId());
            }

            if (env.isIdle(1440)) {
                log.info("Stopping idle environment: {}", env.getId());
                env.stop();
            }
        }
    }

    public Map<String, Long> getStatistics() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", (long) envStore.size());
        stats.put("running", envStore.values().stream().filter(e -> "RUNNING".equals(e.getStatus())).count());
        stats.put("stopped", envStore.values().stream().filter(e -> "STOPPED".equals(e.getStatus())).count());
        stats.put("creating", envStore.values().stream().filter(e -> "CREATING".equals(e.getStatus())).count());
        stats.put("totalUsageMinutes", envStore.values().stream().mapToLong(PreviewEnvironment::getUsageMinutes).sum());
        return stats;
    }

    public Map<String, Object> getUsageStatistics(String owner) {
        List<PreviewEnvironment> userEnvs = list(owner, null, null);
        Map<String, Object> usage = new HashMap<>();
        usage.put("environmentCount", userEnvs.size());
        usage.put("runningCount", userEnvs.stream().filter(e -> "RUNNING".equals(e.getStatus())).count());
        usage.put("totalUsageMinutes", userEnvs.stream().mapToLong(PreviewEnvironment::getUsageMinutes).sum());
        return usage;
    }
}
