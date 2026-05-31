package com.modelguard.service.gpu;

import com.modelguard.common.PageResult;
import com.modelguard.dto.request.GpuNodeRegisterRequest;
import com.modelguard.dto.request.HeartbeatRequest;
import com.modelguard.dto.response.GpuNodeResponse;
import com.modelguard.entity.GpuNode;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface GpuNodeService {

    Mono<GpuNodeResponse> registerNode(GpuNodeRegisterRequest request);

    Mono<GpuNodeResponse> getNode(String nodeId);

    Mono<GpuNode> getNodeEntity(String nodeId);

    Mono<GpuNodeResponse> updateNodeStatus(String nodeId, HeartbeatRequest request);

    Mono<GpuNodeResponse> updateNodeResources(String nodeId, Map<String, Object> resources);

    Mono<Boolean> removeNode(String nodeId);

    Mono<List<GpuNodeResponse>> listNodes(String status);

    Mono<PageResult<GpuNodeResponse>> pageNodes(String status, int pageNum, int pageSize);

    Mono<List<GpuNodeResponse>> listAvailableNodes(Integer requiredGpuCount, Integer requiredMemoryGb);

    Mono<GpuNodeResponse> markNodeOffline(String nodeId);

    Mono<GpuNodeResponse> markNodeOnline(String nodeId);

    Mono<Boolean> checkNodeHeartbeat(String nodeId, int timeoutSeconds);

    Mono<GpuNode> ensureNodeAvailable(String nodeId);
}
