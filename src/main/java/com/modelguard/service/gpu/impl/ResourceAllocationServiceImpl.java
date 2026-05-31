package com.modelguard.service.gpu.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.modelguard.entity.GpuNode;
import com.modelguard.entity.GpuTask;
import com.modelguard.mapper.GpuNodeMapper;
import com.modelguard.service.gpu.ResourceAllocationService;
import com.modelguard.util.ReactiveBridgeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceAllocationServiceImpl implements ResourceAllocationService {

    private final GpuNodeMapper gpuNodeMapper;

    @Override
    public Mono<GpuNode> findBestFitNode(GpuTask task, List<GpuNode> availableNodes) {
        return Mono.fromCallable(() -> {
            int requiredGpus = task.getGpuCount() != null ? task.getGpuCount() : 1;
            int requiredMemory = task.getMemoryGb() != null ? task.getMemoryGb() : 8;

            GpuNode bestNode = null;
            int bestScore = Integer.MIN_VALUE;

            for (GpuNode node : availableNodes) {
                if (canAllocateSync(node, requiredGpus, requiredMemory)) {
                    int score = calculateNodeScoreSync(node, task);
                    if (score > bestScore) {
                        bestScore = score;
                        bestNode = node;
                    }
                }
            }

            return bestNode;
        });
    }

    @Override
    public Mono<GpuNode> findFirstFitNode(GpuTask task, List<GpuNode> availableNodes) {
        return Mono.fromCallable(() -> {
            int requiredGpus = task.getGpuCount() != null ? task.getGpuCount() : 1;
            int requiredMemory = task.getMemoryGb() != null ? task.getMemoryGb() : 8;

            for (GpuNode node : availableNodes) {
                if (canAllocateSync(node, requiredGpus, requiredMemory)) {
                    return node;
                }
            }
            return null;
        });
    }

    @Override
    public Mono<List<Integer>> allocateGpus(GpuNode node, int gpuCount, int memoryPerGpuGb) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            List<Integer> allocatedGpus = new ArrayList<>();
            List<Integer> currentAllocated = node.getAllocatedGpus() != null ?
                    new ArrayList<>(node.getAllocatedGpus()) : new ArrayList<>();

            for (int i = 0; i < node.getTotalGpuCount() && allocatedGpus.size() < gpuCount; i++) {
                if (!currentAllocated.contains(i)) {
                    allocatedGpus.add(i);
                    currentAllocated.add(i);
                }
            }

            if (allocatedGpus.size() < gpuCount) {
                throw new IllegalStateException("Not enough GPUs available on node: " + node.getNodeId());
            }

            node.setAllocatedGpus(currentAllocated);
            node.setAvailableGpuCount(node.getAvailableGpuCount() - gpuCount);
            node.setAvailableMemoryGb(node.getAvailableMemoryGb() - (gpuCount * memoryPerGpuGb));

            gpuNodeMapper.updateById(node);
            log.info("Allocated GPUs {} on node {}", allocatedGpus, node.getNodeId());
            return allocatedGpus;
        });
    }

    @Override
    public Mono<Boolean> releaseGpus(String nodeId, List<Integer> gpuIndices) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuNode::getNodeId, nodeId);
            GpuNode node = gpuNodeMapper.selectOne(wrapper);
            if (node == null) {
                return false;
            }

            List<Integer> currentAllocated = node.getAllocatedGpus() != null ?
                    new ArrayList<>(node.getAllocatedGpus()) : new ArrayList<>();
            currentAllocated.removeAll(gpuIndices);
            node.setAllocatedGpus(currentAllocated);
            node.setAvailableGpuCount(node.getAvailableGpuCount() + gpuIndices.size());

            gpuNodeMapper.updateById(node);
            log.info("Released GPUs {} on node {}", gpuIndices, nodeId);
            return true;
        });
    }

    @Override
    public Mono<Boolean> canAllocate(GpuNode node, int gpuCount, int memoryPerGpuGb) {
        return Mono.fromCallable(() -> canAllocateSync(node, gpuCount, memoryPerGpuGb));
    }

    private boolean canAllocateSync(GpuNode node, int gpuCount, int memoryPerGpuGb) {
        if (node.getAvailableGpuCount() == null || node.getAvailableGpuCount() < gpuCount) {
            return false;
        }
        if (node.getAvailableMemoryGb() == null || node.getAvailableMemoryGb() < (gpuCount * memoryPerGpuGb)) {
            return false;
        }
        return true;
    }

    @Override
    public Mono<Map<String, Object>> calculateNodeUtilization(GpuNode node) {
        return Mono.fromCallable(() -> {
            Map<String, Object> utilization = new HashMap<>();

            int totalGpus = node.getTotalGpuCount() != null ? node.getTotalGpuCount() : 0;
            int availableGpus = node.getAvailableGpuCount() != null ? node.getAvailableGpuCount() : 0;
            int usedGpus = totalGpus - availableGpus;

            int totalMemory = node.getTotalMemoryGb() != null ? node.getTotalMemoryGb() : 0;
            int availableMemory = node.getAvailableMemoryGb() != null ? node.getAvailableMemoryGb() : 0;
            int usedMemory = totalMemory - availableMemory;

            utilization.put("nodeId", node.getNodeId());
            utilization.put("gpuUtilization", totalGpus > 0 ? (double) usedGpus / totalGpus * 100 : 0);
            utilization.put("memoryUtilization", totalMemory > 0 ? (double) usedMemory / totalMemory * 100 : 0);
            utilization.put("usedGpus", usedGpus);
            utilization.put("totalGpus", totalGpus);
            utilization.put("availableGpus", availableGpus);
            utilization.put("usedMemoryGb", usedMemory);
            utilization.put("totalMemoryGb", totalMemory);
            utilization.put("availableMemoryGb", availableMemory);
            utilization.put("status", node.getStatus());

            return utilization;
        });
    }

    @Override
    public Mono<Integer> calculateNodeScore(GpuNode node, GpuTask task) {
        return Mono.fromCallable(() -> calculateNodeScoreSync(node, task));
    }

    private int calculateNodeScoreSync(GpuNode node, GpuTask task) {
        int score = 0;

        int availableGpus = node.getAvailableGpuCount() != null ? node.getAvailableGpuCount() : 0;
        int requiredGpus = task.getGpuCount() != null ? task.getGpuCount() : 1;
        score += (availableGpus - requiredGpus) * 10;

        int availableMemory = node.getAvailableMemoryGb() != null ? node.getAvailableMemoryGb() : 0;
        int requiredMemory = task.getMemoryGb() != null ? task.getMemoryGb() : 8;
        score += (availableMemory - requiredMemory);

        if (node.getGpuUtilization() != null) {
            score -= node.getGpuUtilization().intValue();
        }

        if ("ONLINE".equals(node.getStatus())) {
            score += 1000;
        }

        return score;
    }

    @Override
    public Mono<Integer> getAvailableGpuCount(GpuNode node, int requiredMemoryGb) {
        return Mono.fromCallable(() -> {
            if (node.getAvailableGpuCount() == null || node.getAvailableMemoryGb() == null) {
                return 0;
            }

            int maxByGpu = node.getAvailableGpuCount();
            int maxByMemory = node.getAvailableMemoryGb() / Math.max(1, requiredMemoryGb);

            return Math.min(maxByGpu, maxByMemory);
        });
    }

    @Override
    public Mono<Boolean> checkResourceDeadlock() {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuNode::getStatus, "ONLINE");
            List<GpuNode> onlineNodes = gpuNodeMapper.selectList(wrapper);

            int totalAvailableGpus = onlineNodes.stream()
                    .mapToInt(n -> n.getAvailableGpuCount() != null ? n.getAvailableGpuCount() : 0)
                    .sum();

            return totalAvailableGpus == 0 && !onlineNodes.isEmpty();
        });
    }

    @Override
    public Mono<Map<String, Object>> getGlobalResourceSnapshot() {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            List<GpuNode> allNodes = gpuNodeMapper.selectList(wrapper);

            Map<String, Object> snapshot = new HashMap<>();

            int totalNodes = allNodes.size();
            int onlineNodes = (int) allNodes.stream().filter(n -> "ONLINE".equals(n.getStatus())).count();
            int offlineNodes = totalNodes - onlineNodes;

            int totalGpus = allNodes.stream()
                    .mapToInt(n -> n.getTotalGpuCount() != null ? n.getTotalGpuCount() : 0)
                    .sum();
            int availableGpus = allNodes.stream()
                    .mapToInt(n -> n.getAvailableGpuCount() != null ? n.getAvailableGpuCount() : 0)
                    .sum();
            int totalMemory = allNodes.stream()
                    .mapToInt(n -> n.getTotalMemoryGb() != null ? n.getTotalMemoryGb() : 0)
                    .sum();
            int availableMemory = allNodes.stream()
                    .mapToInt(n -> n.getAvailableMemoryGb() != null ? n.getAvailableMemoryGb() : 0)
                    .sum();

            snapshot.put("totalNodes", totalNodes);
            snapshot.put("onlineNodes", onlineNodes);
            snapshot.put("offlineNodes", offlineNodes);
            snapshot.put("totalGpus", totalGpus);
            snapshot.put("availableGpus", availableGpus);
            snapshot.put("usedGpus", totalGpus - availableGpus);
            snapshot.put("totalMemoryGb", totalMemory);
            snapshot.put("availableMemoryGb", availableMemory);
            snapshot.put("usedMemoryGb", totalMemory - availableMemory);
            snapshot.put("gpuUtilization", totalGpus > 0 ? (double) (totalGpus - availableGpus) / totalGpus * 100 : 0);
            snapshot.put("memoryUtilization", totalMemory > 0 ? (double) (totalMemory - availableMemory) / totalMemory * 100 : 0);
            snapshot.put("timestamp", System.currentTimeMillis());

            Map<String, Object> nodeDetails = new HashMap<>();
            for (GpuNode node : allNodes) {
                nodeDetails.put(node.getNodeId(), calculateNodeUtilizationSync(node));
            }
            snapshot.put("nodes", nodeDetails);

            return snapshot;
        });
    }

    private Map<String, Object> calculateNodeUtilizationSync(GpuNode node) {
        Map<String, Object> utilization = new HashMap<>();

        int totalGpus = node.getTotalGpuCount() != null ? node.getTotalGpuCount() : 0;
        int availableGpus = node.getAvailableGpuCount() != null ? node.getAvailableGpuCount() : 0;

        int totalMemory = node.getTotalMemoryGb() != null ? node.getTotalMemoryGb() : 0;
        int availableMemory = node.getAvailableMemoryGb() != null ? node.getAvailableMemoryGb() : 0;

        utilization.put("gpuUtilization", totalGpus > 0 ? (double) (totalGpus - availableGpus) / totalGpus * 100 : 0);
        utilization.put("memoryUtilization", totalMemory > 0 ? (double) (totalMemory - availableMemory) / totalMemory * 100 : 0);
        utilization.put("status", node.getStatus());
        utilization.put("availableGpus", availableGpus);
        utilization.put("availableMemoryGb", availableMemory);

        return utilization;
    }
}
