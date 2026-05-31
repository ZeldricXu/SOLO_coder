package com.modelguard.service;

import com.modelguard.common.PageResult;
import com.modelguard.dto.GpuNodeDTO;
import com.modelguard.dto.GpuTaskSubmitDTO;
import com.modelguard.dto.HeartbeatDTO;
import com.modelguard.entity.GpuNode;
import com.modelguard.entity.GpuTask;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface GpuSchedulerService {

    Mono<GpuNode> registerNode(GpuNodeDTO dto);

    Mono<GpuNode> getNode(String nodeId);

    Mono<List<GpuNode>> listNodes(String status);

    Mono<GpuNode> updateNodeStatus(String nodeId, String status);

    Mono<GpuNode> processHeartbeat(HeartbeatDTO dto);

    Mono<Void> removeNode(String nodeId);

    Mono<GpuTask> submitTask(GpuTaskSubmitDTO dto);

    Mono<GpuTask> getTask(String taskId);

    Mono<PageResult<GpuTask>> pageTasks(String status, Integer priority, int pageNum, int pageSize);

    Mono<GpuTask> scheduleTask();

    Mono<GpuTask> startTask(String taskId, String nodeId, String gpuIndices);

    Mono<GpuTask> completeTask(String taskId);

    Mono<GpuTask> failTask(String taskId, String errorDetail);

    Mono<GpuTask> cancelTask(String taskId);

    Mono<Boolean> preemptTask(String taskId, String highPriorityTaskId);

    Mono<Map<String, Object>> getClusterStatus();

    Mono<List<GpuTask>> getPendingQueue();

    Mono<List<GpuTask>> getNodeRunningTasks(String nodeId);

    Mono<Map<String, Object>> getTaskStatus(String taskId);
}
