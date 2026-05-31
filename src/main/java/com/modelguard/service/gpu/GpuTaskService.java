package com.modelguard.service.gpu;

import com.modelguard.common.PageResult;
import com.modelguard.dto.request.GpuTaskSubmitRequest;
import com.modelguard.dto.response.GpuTaskResponse;
import com.modelguard.entity.GpuTask;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface GpuTaskService {

    Mono<GpuTaskResponse> submitTask(GpuTaskSubmitRequest request);

    Mono<GpuTaskResponse> getTask(String taskId);

    Mono<GpuTask> getTaskEntity(String taskId);

    Mono<GpuTaskResponse> updateTaskStatus(String taskId, String status, Map<String, Object> progress);

    Mono<GpuTaskResponse> assignTaskToNode(String taskId, String nodeId, Integer gpuIndex);

    Mono<GpuTaskResponse> markTaskCompleted(String taskId, Map<String, Object> result);

    Mono<GpuTaskResponse> markTaskFailed(String taskId, String errorMessage);

    Mono<Boolean> cancelTask(String taskId);

    Mono<List<GpuTaskResponse>> listTasksByNode(String nodeId, String status);

    Mono<PageResult<GpuTaskResponse>> pageTasks(String status, Integer priority, int pageNum, int pageSize);

    Mono<GpuTaskResponse> increaseTaskPriority(String taskId);

    Mono<GpuTaskResponse> decreaseTaskPriority(String taskId);

    Mono<Boolean> isTaskPreemptible(String taskId);

    Mono<GpuTaskResponse> markTaskPreempted(String taskId, String preemptedByTaskId);

    Mono<List<GpuTaskResponse>> getPendingTasksByPriority();

    Mono<Long> countTasksByStatus(String status);
}
