package com.taskscheduler.loadbalancer;

import com.taskscheduler.config.LoadBalancerConfig;
import com.taskscheduler.entity.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;

@Slf4j
@Component("compositeLoadBalancer")
@RequiredArgsConstructor
public class CompositeLoadBalancer implements LoadBalancerStrategy {

    private final LoadBalancerConfig loadBalancerConfig;

    @Override
    public String getName() {
        return "composite";
    }

    @Override
    public Executor selectExecutor(List<Executor> availableExecutors, String taskType) {
        if (availableExecutors == null || availableExecutors.isEmpty()) {
            throw new IllegalArgumentException("No available executors");
        }

        LoadBalancerConfig.ResourceWeights weights = loadBalancerConfig.getResourceWeights();
        int taskCountWeight = weights.getTaskCount();
        int cpuWeight = weights.getCpu();
        int memoryWeight = weights.getMemory();

        Optional<Executor> bestExecutor = availableExecutors.stream()
                .min(Comparator.comparingDouble(executor ->
                        calculateCompositeScore(executor, taskCountWeight, cpuWeight, memoryWeight));

        if (bestExecutor.isEmpty()) {
            throw new IllegalStateException("Failed to select executor");
        }

        Executor selected = bestExecutor.get();
        double score = calculateCompositeScore(selected, taskCountWeight, cpuWeight, memoryWeight);

        log.debug("Composite selected executor: {}, score: {}", selected.getExecutorId(), score);

        return selected;
    }

    private double calculateCompositeScore(Executor executor, int taskWeight, int cpuWeight, int memoryWeight) {
        ExecutorResourceMetrics metrics = ExecutorResourceMetrics.getOrCreate(executor.getExecutorId());

        int maxCapacity = executor.getMaxCapacity();
        int currentTaskCount = executor.getCurrentLoad();
        double taskLoadRatio = maxCapacity > 0 ? (double) currentTaskCount / maxCapacity : 1.0;
        double taskScore = taskLoadRatio * taskWeight;

        int cpuUsage = metrics.getCurrentCpuUsage();
        double cpuScore = (cpuUsage / 100.0) * cpuWeight;

        int memoryUsage = metrics.getCurrentMemoryUsage();
        double memoryScore = (memoryUsage / 100.0) * memoryWeight;

        double totalWeight = taskWeight + cpuWeight + memoryWeight;
        double compositeScore = (taskScore + cpuScore + memoryScore) / totalWeight * 100;

        log.trace("Executor {}: taskLoad={}%, cpu={}%, memory={}%, score={}",
                executor.getExecutorId(),
                (int) (taskLoadRatio * 100), cpuUsage, memoryUsage, compositeScore);

        return compositeScore;
    }
}
