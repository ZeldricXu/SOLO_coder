package com.taskscheduler.service;

import com.taskscheduler.config.LoadBalancerConfig;
import com.taskscheduler.dto.RegisterExecutorRequest;
import com.taskscheduler.entity.Executor;
import com.taskscheduler.exception.NoAvailableExecutorException;
import com.taskscheduler.loadbalancer.*;
import com.taskscheduler.repository.ExecutorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutorManagerService {

    private final ExecutorRepository executorRepository;
    private final LoadBalancerConfig loadBalancerConfig;
    private final Map<String, LoadBalancerStrategy> strategyMap;

    private static final Map<String, LoadBalancerStrategy> STRATEGY_CACHE = new ConcurrentHashMap<>();

    @Transactional
    public Executor registerExecutor(RegisterExecutorRequest request) {
        String executorId = request.getExecutorId() != null
                ? request.getExecutorId()
                : "executor_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        Executor executor;
        Optional<Executor> existing = executorRepository.findByExecutorId(executorId);

        if (existing.isPresent()) {
            executor = existing.get();
            executor.setLastActive(LocalDateTime.now());
            executor.setExecutorStatus("online");
            if (request.getExecutorName() != null) {
                executor.setExecutorName(request.getExecutorName());
            }
            if (request.getExecutorAddress() != null) {
                executor.setExecutorAddress(request.getExecutorAddress());
            }
            if (request.getMaxCapacity() != null) {
                executor.setMaxCapacity(request.getMaxCapacity());
            }
            if (request.getTaskType() != null) {
                executor.setTaskType(request.getTaskType());
            }
            log.info("Executor re-registered: {}", executorId);
        } else {
            executor = new Executor();
            executor.setExecutorId(executorId);
            executor.setExecutorName(request.getExecutorName() != null ? request.getExecutorName() : "Executor-" + executorId);
            executor.setExecutorAddress(request.getExecutorAddress());
            executor.setExecutorStatus("online");
            executor.setCurrentLoad(0);
            executor.setMaxCapacity(request.getMaxCapacity() != null ? request.getMaxCapacity() : 10);
            executor.setTaskType(request.getTaskType());
            log.info("Executor registered: {}", executorId);
        }

        ExecutorResourceMetrics metrics = ExecutorResourceMetrics.getOrCreate(executorId);
        log.info("Initialized resource metrics for executor: {}", executorId);

        return executorRepository.save(executor);
    }

    @Transactional
    public void unregisterExecutor(String executorId) {
        executorRepository.findByExecutorId(executorId).ifPresent(executor -> {
            executor.setExecutorStatus("offline");
            executorRepository.save(executor);
            ExecutorResourceMetrics.remove(executorId);
            log.info("Executor unregistered: {}", executorId);
        });
    }

    @Transactional
    public void heartbeat(String executorId) {
        executorRepository.findByExecutorId(executorId).ifPresent(executor -> {
            executor.setLastActive(LocalDateTime.now());
            executor.setExecutorStatus("online");
            executorRepository.save(executor);
        });
    }

    @Transactional
    public void updateExecutorMetrics(String executorId, int cpuUsage, int memoryUsage) {
        ExecutorResourceMetrics metrics = ExecutorResourceMetrics.getOrCreate(executorId);
        metrics.updateCpuUsage(cpuUsage);
        metrics.updateMemoryUsage(memoryUsage);
        log.debug("Updated metrics for executor {}: CPU={}%, Memory={}%", executorId, cpuUsage, memoryUsage);
    }

    @Transactional
    public Executor selectExecutor(String taskType) {
        List<Executor> availableExecutors;

        if (taskType != null && !taskType.isEmpty()) {
            availableExecutors = executorRepository.findAvailableExecutorsForTaskType(taskType);
        } else {
            availableExecutors = executorRepository.findExecutorsWithAvailableCapacity();
        }

        if (availableExecutors.isEmpty()) {
            throw new NoAvailableExecutorException("No available executor found for task type: " + taskType);
        }

        String strategyName = loadBalancerConfig.getStrategyForTaskType(taskType);
        LoadBalancerStrategy strategy = getStrategy(strategyName);

        if (strategy == null) {
            log.warn("Unknown strategy: {}, fallback to least_load", strategyName);
            strategy = strategyMap.get("leastLoadLoadBalancer");
        }

        log.info("Using load balancer strategy: {} for task type: {}", strategy.getName(), taskType);

        Executor selected = strategy.selectExecutor(availableExecutors, taskType);
        selected.setCurrentLoad(selected.getCurrentLoad() + 1);
        executorRepository.save(selected);

        ExecutorResourceMetrics metrics = ExecutorResourceMetrics.getOrCreate(selected.getExecutorId());
        metrics.incrementTaskCount();

        log.info("Selected executor: {} for task type: {} using strategy: {}",
                selected.getExecutorId(), taskType, strategy.getName());
        return selected;
    }

    @Transactional
    public void releaseExecutor(String executorId) {
        executorRepository.findByExecutorId(executorId).ifPresent(executor -> {
            if (executor.getCurrentLoad() > 0) {
                executor.setCurrentLoad(executor.getCurrentLoad() - 1);
                executorRepository.save(executor);
                log.debug("Released executor: {}, current load: {}", executorId, executor.getCurrentLoad());
            }
        });

        ExecutorResourceMetrics metrics = ExecutorResourceMetrics.getOrCreate(executorId);
        metrics.decrementTaskCount();
    }

    public Optional<Executor> getExecutor(String executorId) {
        return executorRepository.findByExecutorId(executorId);
    }

    public List<Executor> getAllExecutors() {
        return executorRepository.findAll();
    }

    public List<Executor> getOnlineExecutors() {
        return executorRepository.findByExecutorStatus("online");
    }

    @Scheduled(fixedRate = 30000)
    @Transactional
    public void checkAndMarkOfflineExecutors() {
        checkAndMarkOfflineExecutors(60);
    }

    @Transactional
    public void checkAndMarkOfflineExecutors(int timeoutSeconds) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(timeoutSeconds);
        List<Executor> executors = executorRepository.findByExecutorStatus("online");

        for (Executor executor : executors) {
            if (executor.getLastActive().isBefore(threshold)) {
                executor.setExecutorStatus("offline");
                executorRepository.save(executor);
                ExecutorResourceMetrics.remove(executor.getExecutorId());
                log.warn("Executor marked as offline due to inactivity: {}", executor.getExecutorId());
            }
        }
    }

    private LoadBalancerStrategy getStrategy(String strategyName) {
        if (strategyName == null) {
            return strategyMap.get("leastLoadLoadBalancer");
        }

        switch (strategyName.toLowerCase()) {
            case "round_robin":
            case "round-robin":
            case "roundrobin":
                return strategyMap.get("roundRobinLoadBalancer");
            case "weighted":
            case "weight":
                return strategyMap.get("weightedLoadBalancer");
            case "least_load":
            case "least-load":
            case "leastload":
                return strategyMap.get("leastLoadLoadBalancer");
            case "composite":
            case "resource":
            case "resource_aware":
                return strategyMap.get("compositeLoadBalancer");
            default:
                log.warn("Unknown strategy name: {}, using default least_load", strategyName);
                return strategyMap.get("leastLoadLoadBalancer");
        }
    }

    public LoadBalancerStrategy getCurrentStrategy(String taskType) {
        String strategyName = loadBalancerConfig.getStrategyForTaskType(taskType);
        return getStrategy(strategyName);
    }

    public String getCurrentStrategyName(String taskType) {
        return loadBalancerConfig.getStrategyForTaskType(taskType);
    }
}
