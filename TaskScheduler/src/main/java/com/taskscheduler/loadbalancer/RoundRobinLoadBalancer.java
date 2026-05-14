package com.taskscheduler.loadbalancer;

import com.taskscheduler.entity.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component("roundRobinLoadBalancer")
public class RoundRobinLoadBalancer implements LoadBalancerStrategy {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public String getName() {
        return "round_robin";
    }

    @Override
    public Executor selectExecutor(List<Executor> availableExecutors, String taskType) {
        if (availableExecutors == null || availableExecutors.isEmpty()) {
            throw new IllegalArgumentException("No available executors");
        }

        int current = counter.getAndIncrement();
        int index = Math.abs(current) % availableExecutors.size();

        Executor selected = availableExecutors.get(index);
        log.debug("RoundRobin selected executor: {}, index: {}", selected.getExecutorId(), index);

        return selected;
    }
}
