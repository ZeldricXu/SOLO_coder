package com.datapipeline.data.repository;

import com.datapipeline.common.model.RunInstance;
import com.datapipeline.data.cache.CacheManager;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class RunInstanceRepository {

    private final Map<String, RunInstance> store = new ConcurrentHashMap<>();
    private final CacheManager cacheManager;

    public RunInstanceRepository(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public RunInstance save(RunInstance run) {
        if (run.getRunId() == null) {
            throw new IllegalArgumentException("Run ID cannot be null");
        }
        if (run.getStartedAt() == null) {
            run.setStartedAt(Instant.now());
        }
        store.put(run.getRunId(), run);
        cacheManager.invalidate(getCacheKey(run.getRunId()));
        cacheManager.invalidate("run:entity:" + run.getEntityId());
        return run;
    }

    public Optional<RunInstance> findById(String runId) {
        String cacheKey = getCacheKey(runId);
        Optional<RunInstance> cached = cacheManager.get(cacheKey);
        if (cached.isPresent()) {
            return cached;
        }
        RunInstance run = store.get(runId);
        if (run != null) {
            cacheManager.put(cacheKey, run, Duration.ofMinutes(5));
            return Optional.of(run);
        }
        return Optional.empty();
    }

    public List<RunInstance> findByEntityId(String entityId) {
        String cacheKey = "run:entity:" + entityId;
        Optional<List<RunInstance>> cached = cacheManager.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }
        List<RunInstance> results = store.values().stream()
                .filter(r -> entityId.equals(r.getEntityId()))
                .sorted(Comparator.comparing(RunInstance::getStartedAt).reversed())
                .collect(Collectors.toList());
        cacheManager.put(cacheKey, results, Duration.ofMinutes(2));
        return results;
    }

    public List<RunInstance> findByPhase(RunInstance.Phase phase) {
        return store.values().stream()
                .filter(r -> phase == r.getPhase())
                .collect(Collectors.toList());
    }

    public List<RunInstance> findActive() {
        Set<RunInstance.Phase> activePhases = EnumSet.of(
                RunInstance.Phase.INITIALIZING,
                RunInstance.Phase.RUNNING
        );
        return store.values().stream()
                .filter(r -> activePhases.contains(r.getPhase()))
                .collect(Collectors.toList());
    }

    public List<RunInstance> findAll() {
        return new ArrayList<>(store.values());
    }

    public void deleteById(String runId) {
        RunInstance run = store.get(runId);
        if (run != null) {
            store.remove(runId);
            cacheManager.invalidate(getCacheKey(runId));
            cacheManager.invalidate("run:entity:" + run.getEntityId());
        }
    }

    public Optional<RunInstance> findLatestByEntityId(String entityId) {
        return store.values().stream()
                .filter(r -> entityId.equals(r.getEntityId()))
                .max(Comparator.comparing(RunInstance::getStartedAt));
    }

    private String getCacheKey(String runId) {
        return "run:" + runId;
    }

}
