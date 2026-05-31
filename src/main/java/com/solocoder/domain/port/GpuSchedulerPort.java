package com.solocoder.domain.port;

import com.solocoder.domain.model.RunInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface GpuSchedulerPort {

    Mono<RunInstance> submitTask(String taskName, int priority, int gpuRequirement,
                                  Map<String, Object> parameters, Runnable task);

    Mono<Void> cancelTask(String taskId);

    Mono<RunInstance> getTaskStatus(String taskId);

    Flux<RunInstance> listTasks(String status);

    Mono<Void> preemptTask(String taskId);

    Map<String, Object> getClusterStatus();

    Mono<Void> adjustTaskPriority(String taskId, int newPriority);
}
