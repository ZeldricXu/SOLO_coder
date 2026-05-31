package com.modelguard.service.gpu;

import com.modelguard.dto.response.GpuTaskResponse;
import com.modelguard.entity.GpuTask;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface GpuSchedulerService {

    Mono<GpuTaskResponse> scheduleNextTask();

    Mono<List<GpuTaskResponse>> scheduleBatch(int batchSize);

    Mono<Boolean> checkAndHandlePreemption(GpuTask highPriorityTask);

    Mono<GpuTask> findPreemptibleTask(GpuTask highPriorityTask);

    Mono<Boolean> preemptTask(GpuTask runningTask, GpuTask highPriorityTask);

    Mono<Boolean> requeuePreemptedTask(GpuTask preemptedTask);

    Mono<Map<String, Object>> getSchedulerStatus();

    Mono<Boolean> pauseScheduler();

    Mono<Boolean> resumeScheduler();

    Mono<Boolean> isSchedulerPaused();

    Mono<Integer> getPendingQueueSize();

    Mono<Integer> getRunningTaskCount();

    Mono<Map<String, Object>> getQueueStats();
}
