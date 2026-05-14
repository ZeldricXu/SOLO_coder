package com.taskscheduler.loadbalancer;

import com.taskscheduler.entity.Executor;
import java.util.List;

public interface LoadBalancerStrategy {

    String getName();

    Executor selectExecutor(List<Executor> availableExecutors, String taskType);
}
