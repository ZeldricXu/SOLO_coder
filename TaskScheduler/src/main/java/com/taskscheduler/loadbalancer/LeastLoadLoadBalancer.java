package com.taskscheduler.loadbalancer;

import com.taskscheduler.entity.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component("leastLoadLoadBalancer")
public class LeastLoadLoadBalancer implements LoadBalancerStrategy {

    @Override
    public String getName() {
        return "least_load";
    }

    @Override
    public Executor selectExecutor(List<Executor> availableExecutors, String taskType) {
        if (availableExecutors == null || availableExecutors.isEmpty()) {
            throw new IllegalArgumentException("No available executors");
        }

        Optional<Executor> bestExecutor = availableExecutors.stream()
                .min(Comparator.comparingInt(this::calculateCompositeLoad));

        if (bestExecutor.isEmpty()) {
            throw new IllegalStateException("Failed to select executor");
        }

        Executor selected = bestExecutor.get();
        log.debug("LeastLoad selected executor: {}, compositeLoad: {}",
                selected.getExecutorId(), calculateCompositeLoad(selected));

        return selected;
    }

    private int calculateCompositeLoad(Executor executor) {
        int taskLoad = executor.getCurrentLoad();
        int maxCapacity = executor.getMaxCapacity();

        double loadRatio = maxCapacity > 0 ? (double) taskLoad / maxCapacity : 1.0;

        int compositeLoad = (int) (loadRatio * 100);

        log.trace("Executor {}: taskLoad={}, maxCapacity={}, loadRatio={}, compositeLoad={}",
                executor.getExecutorId(), taskLoad, maxCapacity, loadRatio, compositeLoad);

        return compositeLoad;
    }
}
