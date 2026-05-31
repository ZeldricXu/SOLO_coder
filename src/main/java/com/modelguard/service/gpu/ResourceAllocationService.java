package com.modelguard.service.gpu;

import com.modelguard.entity.GpuNode;
import com.modelguard.entity.GpuTask;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface ResourceAllocationService {

    Mono<GpuNode> findBestFitNode(GpuTask task, List<GpuNode> availableNodes);

    Mono<GpuNode> findFirstFitNode(GpuTask task, List<GpuNode> availableNodes);

    Mono<List<Integer>> allocateGpus(GpuNode node, int gpuCount, int memoryPerGpuGb);

    Mono<Boolean> releaseGpus(String nodeId, List<Integer> gpuIndices);

    Mono<Boolean> canAllocate(GpuNode node, int gpuCount, int memoryPerGpuGb);

    Mono<Map<String, Object>> calculateNodeUtilization(GpuNode node);

    Mono<Integer> calculateNodeScore(GpuNode node, GpuTask task);

    Mono<Integer> getAvailableGpuCount(GpuNode node, int requiredMemoryGb);

    Mono<Boolean> checkResourceDeadlock();

    Mono<Map<String, Object>> getGlobalResourceSnapshot();
}
