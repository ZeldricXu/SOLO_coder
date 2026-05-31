package com.modelguard.service.gpu.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.PageResult;
import com.modelguard.converter.EntityConverter;
import com.modelguard.dto.request.GpuNodeRegisterRequest;
import com.modelguard.dto.request.HeartbeatRequest;
import com.modelguard.dto.response.GpuNodeResponse;
import com.modelguard.entity.GpuNode;
import com.modelguard.exception.BusinessException;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.mapper.GpuNodeMapper;
import com.modelguard.service.gpu.GpuNodeService;
import com.modelguard.util.IdGeneratorUtil;
import com.modelguard.util.ReactiveBridgeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GpuNodeServiceImpl implements GpuNodeService {

    private final GpuNodeMapper gpuNodeMapper;

    private static final List<String> VALID_STATUSES = Arrays.asList("ONLINE", "OFFLINE", "MAINTENANCE", "DRAINING");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuNodeResponse> registerNode(GpuNodeRegisterRequest request) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            GpuNode node = EntityConverter.toEntity(request);
            node.setNodeId(IdGeneratorUtil.generateGpuNodeId());
            node.setStatus("ONLINE");
            node.setLastHeartbeat(LocalDateTime.now());

            gpuNodeMapper.insert(node);
            log.info("Registered GPU node: nodeId={}, ip={}", node.getNodeId(), node.getNodeIp());
            return EntityConverter.toResponse(node);
        });
    }

    @Override
    public Mono<GpuNodeResponse> getNode(String nodeId) {
        return getNodeEntity(nodeId)
                .map(EntityConverter::toResponse);
    }

    @Override
    public Mono<GpuNode> getNodeEntity(String nodeId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuNode::getNodeId, nodeId);
            GpuNode node = gpuNodeMapper.selectOne(wrapper);
            if (node == null) {
                throw new ResourceNotFoundException("GpuNode", nodeId);
            }
            return node;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuNodeResponse> updateNodeStatus(String nodeId, HeartbeatRequest request) {
        return getNodeEntity(nodeId)
                .flatMap(node -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    node.setLastHeartbeat(LocalDateTime.now());

                    if (request.getAvailableGpuCount() != null) {
                        node.setAvailableGpuCount(request.getAvailableGpuCount());
                    }
                    if (request.getAvailableMemoryGb() != null) {
                        node.setAvailableMemoryGb(request.getAvailableMemoryGb());
                    }
                    if (request.getGpuUtilization() != null) {
                        node.setGpuUtilization(request.getGpuUtilization());
                    }
                    if (request.getMemoryUtilization() != null) {
                        node.setMemoryUtilization(request.getMemoryUtilization());
                    }
                    if (request.getStatus() != null && VALID_STATUSES.contains(request.getStatus())) {
                        node.setStatus(request.getStatus());
                    }

                    gpuNodeMapper.updateById(node);
                    log.debug("Updated GPU node heartbeat: nodeId={}", nodeId);
                    return EntityConverter.toResponse(node);
                }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuNodeResponse> updateNodeResources(String nodeId, Map<String, Object> resources) {
        return getNodeEntity(nodeId)
                .flatMap(node -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    if (resources.get("totalGpuCount") instanceof Number) {
                        node.setTotalGpuCount(((Number) resources.get("totalGpuCount")).intValue());
                    }
                    if (resources.get("totalMemoryGb") instanceof Number) {
                        node.setTotalMemoryGb(((Number) resources.get("totalMemoryGb")).intValue());
                    }
                    if (resources.get("availableGpuCount") instanceof Number) {
                        node.setAvailableGpuCount(((Number) resources.get("availableGpuCount")).intValue());
                    }
                    if (resources.get("availableMemoryGb") instanceof Number) {
                        node.setAvailableMemoryGb(((Number) resources.get("availableMemoryGb")).intValue());
                    }
                    if (resources.get("gpuNames") instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> gpuNames = (List<String>) resources.get("gpuNames");
                        node.setGpuNames(gpuNames);
                    }
                    if (resources.get("allocatedGpus") instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Integer> allocatedGpus = (List<Integer>) resources.get("allocatedGpus");
                        node.setAllocatedGpus(allocatedGpus);
                    }

                    gpuNodeMapper.updateById(node);
                    log.info("Updated GPU node resources: nodeId={}", nodeId);
                    return EntityConverter.toResponse(node);
                }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Boolean> removeNode(String nodeId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuNode::getNodeId, nodeId);
            int deleted = gpuNodeMapper.delete(wrapper);
            log.info("Removed GPU node: nodeId={}, deleted={}", nodeId, deleted);
            return deleted > 0;
        });
    }

    @Override
    public Mono<List<GpuNodeResponse>> listNodes(String status) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(GpuNode::getStatus, status);
            }
            wrapper.orderByAsc(GpuNode::getNodeId);
            return gpuNodeMapper.selectList(wrapper).stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    public Mono<PageResult<GpuNodeResponse>> pageNodes(String status, int pageNum, int pageSize) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            Page<GpuNode> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(GpuNode::getStatus, status);
            }
            wrapper.orderByAsc(GpuNode::getNodeId);
            Page<GpuNode> result = gpuNodeMapper.selectPage(page, wrapper);

            List<GpuNodeResponse> responses = result.getRecords().stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());

            return PageResult.of(responses, result.getTotal(), pageNum, pageSize);
        });
    }

    @Override
    public Mono<List<GpuNodeResponse>> listAvailableNodes(Integer requiredGpuCount, Integer requiredMemoryGb) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuNode::getStatus, "ONLINE");
            if (requiredGpuCount != null) {
                wrapper.ge(GpuNode::getAvailableGpuCount, requiredGpuCount);
            }
            if (requiredMemoryGb != null) {
                wrapper.ge(GpuNode::getAvailableMemoryGb, requiredMemoryGb);
            }
            wrapper.orderByDesc(GpuNode::getAvailableGpuCount)
                    .orderByDesc(GpuNode::getAvailableMemoryGb);

            return gpuNodeMapper.selectList(wrapper).stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuNodeResponse> markNodeOffline(String nodeId) {
        return getNodeEntity(nodeId)
                .flatMap(node -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    node.setStatus("OFFLINE");
                    node.setAvailableGpuCount(0);
                    node.setAvailableMemoryGb(0);
                    gpuNodeMapper.updateById(node);
                    log.info("Marked GPU node offline: nodeId={}", nodeId);
                    return EntityConverter.toResponse(node);
                }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuNodeResponse> markNodeOnline(String nodeId) {
        return getNodeEntity(nodeId)
                .flatMap(node -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    node.setStatus("ONLINE");
                    node.setLastHeartbeat(LocalDateTime.now());
                    gpuNodeMapper.updateById(node);
                    log.info("Marked GPU node online: nodeId={}", nodeId);
                    return EntityConverter.toResponse(node);
                }));
    }

    @Override
    public Mono<Boolean> checkNodeHeartbeat(String nodeId, int timeoutSeconds) {
        return getNodeEntity(nodeId)
                .map(node -> {
                    LocalDateTime timeout = LocalDateTime.now().minusSeconds(timeoutSeconds);
                    return node.getLastHeartbeat() != null && node.getLastHeartbeat().isAfter(timeout);
                });
    }

    @Override
    public Mono<GpuNode> ensureNodeAvailable(String nodeId) {
        return getNodeEntity(nodeId)
                .flatMap(node -> {
                    if (!"ONLINE".equals(node.getStatus())) {
                        throw new BusinessException("GPU node is not available: " + node.getStatus());
                    }
                    if (node.getAvailableGpuCount() == null || node.getAvailableGpuCount() <= 0) {
                        throw new BusinessException("GPU node has no available GPUs");
                    }
                    return checkNodeHeartbeat(nodeId, 300)
                            .flatMap(alive -> {
                                if (!alive) {
                                    throw new BusinessException("GPU node heartbeat timeout");
                                }
                                return Mono.just(node);
                            });
                });
    }
}
