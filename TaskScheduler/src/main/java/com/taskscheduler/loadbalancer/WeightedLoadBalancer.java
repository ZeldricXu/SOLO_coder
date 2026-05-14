package com.taskscheduler.loadbalancer;

import com.taskscheduler.entity.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component("weightedLoadBalancer")
public class WeightedLoadBalancer implements LoadBalancerStrategy {

    private final Map<String, Integer> executorWeights = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "weighted";
    }

    @Override
    public Executor selectExecutor(List<Executor> availableExecutors, String taskType) {
        if (availableExecutors == null || availableExecutors.isEmpty()) {
            throw new IllegalArgumentException("No available executors");
        }

        int totalWeight = 0;
        NavigableMap<Integer, Executor> weightMap = new TreeMap<>();

        for (Executor executor : availableExecutors) {
            int weight = getExecutorWeight(executor);
            totalWeight += weight;
            weightMap.put(totalWeight, executor);
        }

        if (totalWeight == 0) {
            log.warn("Total weight is zero, fallback to first executor");
            return availableExecutors.get(0);
        }

        Random random = new Random();
        int randomWeight = random.nextInt(totalWeight);
        Executor selected = weightMap.higherEntry(randomWeight).getValue();

        log.debug("Weighted selected executor: {}, totalWeight: {}, randomWeight: {}",
                selected.getExecutorId(), totalWeight, randomWeight);

        return selected;
    }

    private int getExecutorWeight(Executor executor) {
        Integer cachedWeight = executorWeights.get(executor.getExecutorId());
        if (cachedWeight != null) {
            return cachedWeight;
        }

        int baseWeight = 10;

        int capacity = executor.getMaxCapacity();
        int currentLoad = executor.getCurrentLoad();
        int availableCapacity = Math.max(0, capacity - currentLoad);

        double loadRatio = capacity > 0 ? (double) currentLoad / capacity : 0;
        int dynamicWeight = (int) (baseWeight * (1 - loadRatio) * (availableCapacity + 1));

        int finalWeight = Math.max(1, dynamicWeight);
        executorWeights.put(executor.getExecutorId(), finalWeight);

        log.debug("Executor {} weight calculated: {}", executor.getExecutorId(), finalWeight);
        return finalWeight;
    }

    public void setExecutorWeight(String executorId, int weight) {
        executorWeights.put(executorId, Math.max(1, weight));
    }

    public void clearCache() {
        executorWeights.clear();
    }
}
