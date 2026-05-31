package com.modelguard.service.gpu.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.modelguard.dto.response.ClusterStatusResponse;
import com.modelguard.entity.GpuNode;
import com.modelguard.entity.GpuTask;
import com.modelguard.mapper.GpuNodeMapper;
import com.modelguard.mapper.GpuTaskMapper;
import com.modelguard.service.gpu.GpuClusterMonitorService;
import com.modelguard.util.ReactiveBridgeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GpuClusterMonitorServiceImpl implements GpuClusterMonitorService {

    private final GpuNodeMapper gpuNodeMapper;
    private final GpuTaskMapper gpuTaskMapper;

    @Override
    public Mono<ClusterStatusResponse> getClusterStatus() {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            List<GpuNode> allNodes = gpuNodeMapper.selectList(null);

            int totalNodes = allNodes.size();
            int onlineNodes = 0;
            int offlineNodes = 0;
            int totalGpuCount = 0;
            int totalGpuMemoryGb = 0;
            int availableGpuMemoryGb = 0;

            Map<String, Object> utilizationByNode = new LinkedHashMap<>();

            for (GpuNode node : allNodes) {
                String status = node.getStatus();
                if ("ONLINE".equals(status)) {
                    onlineNodes++;
                } else {
                    offlineNodes++;
                }

                totalGpuCount += node.getTotalGpuCount() != null ? node.getTotalGpuCount() : 0;
                totalGpuMemoryGb += node.getTotalMemoryGb() != null ? node.getTotalMemoryGb() : 0;
                availableGpuMemoryGb += node.getAvailableMemoryGb() != null ? node.getAvailableMemoryGb() : 0;

                Map<String, Object> nodeUtil = new HashMap<>();
                int usedGpus = (node.getTotalGpuCount() != null ? node.getTotalGpuCount() : 0) -
                        (node.getAvailableGpuCount() != null ? node.getAvailableGpuCount() : 0);
                int totalMem = node.getTotalMemoryGb() != null ? node.getTotalMemoryGb() : 0;
                int usedMem = totalMem - (node.getAvailableMemoryGb() != null ? node.getAvailableMemoryGb() : 0);

                nodeUtil.put("status", status);
                nodeUtil.put("gpuCount", node.getTotalGpuCount());
                nodeUtil.put("usedGpus", usedGpus);
                nodeUtil.put("gpuUtilization", totalMem > 0 ? (double) usedMem / totalMem * 100 : 0);
                nodeUtil.put("memoryUtilization", totalMem > 0 ? (double) usedMem / totalMem * 100 : 0);
                nodeUtil.put("lastHeartbeat", node.getLastHeartbeat());
                utilizationByNode.put(node.getNodeId(), nodeUtil);
            }

            double memoryUtilization = totalGpuMemoryGb > 0 ?
                    (double) (totalGpuMemoryGb - availableGpuMemoryGb) / totalGpuMemoryGb * 100 : 0;

            LambdaQueryWrapper<GpuTask> pendingWrapper = new LambdaQueryWrapper<>();
            pendingWrapper.eq(GpuTask::getStatus, "PENDING");
            int pendingTasks = Math.toIntExact(gpuTaskMapper.selectCount(pendingWrapper));

            LambdaQueryWrapper<GpuTask> runningWrapper = new LambdaQueryWrapper<>();
            runningWrapper.eq(GpuTask::getStatus, "RUNNING");
            int runningTasks = Math.toIntExact(gpuTaskMapper.selectCount(runningWrapper));

            LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
            LambdaQueryWrapper<GpuTask> completedWrapper = new LambdaQueryWrapper<>();
            completedWrapper.eq(GpuTask::getStatus, "COMPLETED")
                    .ge(GpuTask::getCompletedAt, todayStart);
            int completedTasksToday = Math.toIntExact(gpuTaskMapper.selectCount(completedWrapper));

            Map<String, Object> queueStats = new HashMap<>();
            Map<Integer, Long> priorityCounts = new HashMap<>();
            for (int i = 0; i <= 9; i++) {
                LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(GpuTask::getStatus, "PENDING")
                        .eq(GpuTask::getPriority, i);
                Long count = gpuTaskMapper.selectCount(wrapper);
                if (count > 0) {
                    priorityCounts.put(i, count);
                }
            }
            queueStats.put("byPriority", priorityCounts);

            return ClusterStatusResponse.builder()
                    .totalNodes(totalNodes)
                    .onlineNodes(onlineNodes)
                    .offlineNodes(offlineNodes)
                    .totalGpuCount(totalGpuCount)
                    .totalGpuMemoryGb(totalGpuMemoryGb)
                    .availableGpuMemoryGb(availableGpuMemoryGb)
                    .memoryUtilization(memoryUtilization)
                    .pendingTasks(pendingTasks)
                    .runningTasks(runningTasks)
                    .completedTasksToday(completedTasksToday)
                    .utilizationByNode(utilizationByNode)
                    .queueStats(queueStats)
                    .build();
        });
    }

    @Override
    public Mono<Map<String, Object>> getClusterUtilization() {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            List<GpuNode> allNodes = gpuNodeMapper.selectList(null);

            int totalGpus = 0;
            int usedGpus = 0;
            int totalMemory = 0;
            int usedMemory = 0;
            int onlineNodes = 0;

            for (GpuNode node : allNodes) {
                if ("ONLINE".equals(node.getStatus())) {
                    onlineNodes++;
                }
                totalGpus += node.getTotalGpuCount() != null ? node.getTotalGpuCount() : 0;
                usedGpus += (node.getTotalGpuCount() != null ? node.getTotalGpuCount() : 0) -
                        (node.getAvailableGpuCount() != null ? node.getAvailableGpuCount() : 0);
                totalMemory += node.getTotalMemoryGb() != null ? node.getTotalMemoryGb() : 0;
                usedMemory += (node.getTotalMemoryGb() != null ? node.getTotalMemoryGb() : 0) -
                        (node.getAvailableMemoryGb() != null ? node.getAvailableMemoryGb() : 0);
            }

            Map<String, Object> utilization = new HashMap<>();
            utilization.put("totalNodes", allNodes.size());
            utilization.put("onlineNodes", onlineNodes);
            utilization.put("totalGpus", totalGpus);
            utilization.put("usedGpus", usedGpus);
            utilization.put("availableGpus", totalGpus - usedGpus);
            utilization.put("totalMemoryGb", totalMemory);
            utilization.put("usedMemoryGb", usedMemory);
            utilization.put("availableMemoryGb", totalMemory - usedMemory);
            utilization.put("gpuUtilizationPercent", totalGpus > 0 ? (double) usedGpus / totalGpus * 100 : 0);
            utilization.put("memoryUtilizationPercent", totalMemory > 0 ? (double) usedMemory / totalMemory * 100 : 0);
            utilization.put("timestamp", System.currentTimeMillis());

            return utilization;
        });
    }

    @Override
    public Mono<List<Map<String, Object>>> getNodeUtilizationList() {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            List<GpuNode> allNodes = gpuNodeMapper.selectList(null);
            List<Map<String, Object>> result = new ArrayList<>();

            for (GpuNode node : allNodes) {
                Map<String, Object> nodeUtil = new LinkedHashMap<>();
                int totalGpus = node.getTotalGpuCount() != null ? node.getTotalGpuCount() : 0;
                int availableGpus = node.getAvailableGpuCount() != null ? node.getAvailableGpuCount() : 0;
                int totalMem = node.getTotalMemoryGb() != null ? node.getTotalMemoryGb() : 0;
                int availableMem = node.getAvailableMemoryGb() != null ? node.getAvailableMemoryGb() : 0;

                nodeUtil.put("nodeId", node.getNodeId());
                nodeUtil.put("nodeName", node.getNodeName());
                nodeUtil.put("nodeIp", node.getNodeIp());
                nodeUtil.put("status", node.getStatus());
                nodeUtil.put("totalGpus", totalGpus);
                nodeUtil.put("usedGpus", totalGpus - availableGpus);
                nodeUtil.put("availableGpus", availableGpus);
                nodeUtil.put("totalMemoryGb", totalMem);
                nodeUtil.put("usedMemoryGb", totalMem - availableMem);
                nodeUtil.put("availableMemoryGb", availableMem);
                nodeUtil.put("gpuUtilization", totalGpus > 0 ? (double) (totalGpus - availableGpus) / totalGpus * 100 : 0);
                nodeUtil.put("memoryUtilization", totalMem > 0 ? (double) (totalMem - availableMem) / totalMem * 100 : 0);
                nodeUtil.put("gpuUtilizationMetric", node.getGpuUtilization());
                nodeUtil.put("memoryUtilizationMetric", node.getMemoryUtilization());
                nodeUtil.put("lastHeartbeat", node.getLastHeartbeat());
                nodeUtil.put("gpuNames", node.getGpuNames());

                result.add(nodeUtil);
            }

            result.sort((a, b) -> ((String) a.get("nodeId")).compareTo((String) b.get("nodeId")));
            return result;
        });
    }

    @Override
    public Mono<Map<String, Object>> getNodeUtilization(String nodeId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuNode::getNodeId, nodeId);
            GpuNode node = gpuNodeMapper.selectOne(wrapper);

            if (node == null) {
                throw new com.modelguard.exception.ResourceNotFoundException("GpuNode", nodeId);
            }

            Map<String, Object> nodeUtil = new LinkedHashMap<>();
            int totalGpus = node.getTotalGpuCount() != null ? node.getTotalGpuCount() : 0;
            int availableGpus = node.getAvailableGpuCount() != null ? node.getAvailableGpuCount() : 0;
            int totalMem = node.getTotalMemoryGb() != null ? node.getTotalMemoryGb() : 0;
            int availableMem = node.getAvailableMemoryGb() != null ? node.getAvailableMemoryGb() : 0;

            nodeUtil.put("nodeId", node.getNodeId());
            nodeUtil.put("nodeName", node.getNodeName());
            nodeUtil.put("nodeIp", node.getNodeIp());
            nodeUtil.put("status", node.getStatus());
            nodeUtil.put("totalGpus", totalGpus);
            nodeUtil.put("usedGpus", totalGpus - availableGpus);
            nodeUtil.put("availableGpus", availableGpus);
            nodeUtil.put("totalMemoryGb", totalMem);
            nodeUtil.put("usedMemoryGb", totalMem - availableMem);
            nodeUtil.put("availableMemoryGb", availableMem);
            nodeUtil.put("gpuUtilizationPercent", totalGpus > 0 ? (double) (totalGpus - availableGpus) / totalGpus * 100 : 0);
            nodeUtil.put("memoryUtilizationPercent", totalMem > 0 ? (double) (totalMem - availableMem) / totalMem * 100 : 0);
            nodeUtil.put("gpuUtilization", node.getGpuUtilization());
            nodeUtil.put("memoryUtilization", node.getMemoryUtilization());
            nodeUtil.put("lastHeartbeat", node.getLastHeartbeat());
            nodeUtil.put("gpuNames", node.getGpuNames());
            nodeUtil.put("allocatedGpus", node.getAllocatedGpus());
            nodeUtil.put("labels", node.getLabels());

            LambdaQueryWrapper<GpuTask> taskWrapper = new LambdaQueryWrapper<>();
            taskWrapper.eq(GpuTask::getNodeId, nodeId)
                    .eq(GpuTask::getStatus, "RUNNING");
            nodeUtil.put("runningTasks", gpuTaskMapper.selectCount(taskWrapper));

            return nodeUtil;
        });
    }

    @Override
    public Mono<Map<String, Object>> getTaskDistribution() {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            Map<String, Object> distribution = new HashMap<>();

            Map<String, Long> byStatus = new HashMap<>();
            List<String> statuses = Arrays.asList("PENDING", "SCHEDULED", "RUNNING", "COMPLETED", "FAILED", "PREEMPTED", "CANCELLED");
            for (String status : statuses) {
                LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(GpuTask::getStatus, status);
                byStatus.put(status.toLowerCase(), gpuTaskMapper.selectCount(wrapper));
            }
            distribution.put("byStatus", byStatus);

            Map<Integer, Long> byPriority = new HashMap<>();
            for (int i = 0; i <= 9; i++) {
                LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(GpuTask::getPriority, i);
                byPriority.put(i, gpuTaskMapper.selectCount(wrapper));
            }
            distribution.put("byPriority", byPriority);

            Map<String, Long> byNode = new HashMap<>();
            List<GpuNode> nodes = gpuNodeMapper.selectList(null);
            for (GpuNode node : nodes) {
                LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(GpuTask::getNodeId, node.getNodeId());
                byNode.put(node.getNodeId(), gpuTaskMapper.selectCount(wrapper));
            }
            distribution.put("byNode", byNode);

            return distribution;
        });
    }

    @Override
    public Mono<List<Map<String, Object>>> getAlerts() {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            List<Map<String, Object>> alerts = new ArrayList<>();
            LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);

            List<GpuNode> nodes = gpuNodeMapper.selectList(null);
            for (GpuNode node : nodes) {
                if ("ONLINE".equals(node.getStatus()) &&
                        (node.getLastHeartbeat() == null || node.getLastHeartbeat().isBefore(fiveMinutesAgo))) {
                    Map<String, Object> alert = new HashMap<>();
                    alert.put("type", "HEARTBEAT_TIMEOUT");
                    alert.put("severity", "CRITICAL");
                    alert.put("nodeId", node.getNodeId());
                    alert.put("message", "Node heartbeat timeout for 5 minutes");
                    alert.put("timestamp", System.currentTimeMillis());
                    alerts.add(alert);
                }

                if (node.getGpuUtilization() != null && node.getGpuUtilization() > 95) {
                    Map<String, Object> alert = new HashMap<>();
                    alert.put("type", "HIGH_GPU_UTILIZATION");
                    alert.put("severity", "WARNING");
                    alert.put("nodeId", node.getNodeId());
                    alert.put("message", "GPU utilization exceeds 95%: " + node.getGpuUtilization() + "%");
                    alert.put("timestamp", System.currentTimeMillis());
                    alerts.add(alert);
                }

                if (node.getMemoryUtilization() != null && node.getMemoryUtilization() > 95) {
                    Map<String, Object> alert = new HashMap<>();
                    alert.put("type", "HIGH_MEMORY_UTILIZATION");
                    alert.put("severity", "WARNING");
                    alert.put("nodeId", node.getNodeId());
                    alert.put("message", "Memory utilization exceeds 95%: " + node.getMemoryUtilization() + "%");
                    alert.put("timestamp", System.currentTimeMillis());
                    alerts.add(alert);
                }
            }

            LambdaQueryWrapper<GpuTask> pendingWrapper = new LambdaQueryWrapper<>();
            pendingWrapper.eq(GpuTask::getStatus, "PENDING")
                    .ge(GpuTask::getPriority, 8);
            Long highPriorityPending = gpuTaskMapper.selectCount(pendingWrapper);
            if (highPriorityPending > 5) {
                Map<String, Object> alert = new HashMap<>();
                alert.put("type", "QUEUE_BACKLOG");
                alert.put("severity", "WARNING");
                alert.put("message", "High priority pending tasks exceed 5: " + highPriorityPending);
                alert.put("timestamp", System.currentTimeMillis());
                alerts.add(alert);
            }

            return alerts;
        });
    }

    @Override
    public Mono<Map<String, Object>> getHistoricalMetrics(int hours) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            Map<String, Object> metrics = new HashMap<>();
            LocalDateTime startTime = LocalDateTime.now().minusHours(hours);

            LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.ge(GpuTask::getCompletedAt, startTime)
                    .eq(GpuTask::getStatus, "COMPLETED");
            Long completedTasks = gpuTaskMapper.selectCount(wrapper);

            LambdaQueryWrapper<GpuTask> failedWrapper = new LambdaQueryWrapper<>();
            failedWrapper.ge(GpuTask::getCompletedAt, startTime)
                    .eq(GpuTask::getStatus, "FAILED");
            Long failedTasks = gpuTaskMapper.selectCount(failedWrapper);

            LambdaQueryWrapper<GpuTask> preemptedWrapper = new LambdaQueryWrapper<>();
            preemptedWrapper.ge(GpuTask::getCompletedAt, startTime)
                    .eq(GpuTask::getStatus, "PREEMPTED");
            Long preemptedTasks = gpuTaskMapper.selectCount(preemptedWrapper);

            metrics.put("timeRangeHours", hours);
            metrics.put("completedTasks", completedTasks);
            metrics.put("failedTasks", failedTasks);
            metrics.put("preemptedTasks", preemptedTasks);
            metrics.put("totalTasks", completedTasks + failedTasks + preemptedTasks);
            metrics.put("successRate", completedTasks + failedTasks > 0 ?
                    (double) completedTasks / (completedTasks + failedTasks) * 100 : 0);

            return metrics;
        });
    }

    @Override
    public Mono<Boolean> checkClusterHealth() {
        return getAlerts()
                .map(alerts -> alerts.stream()
                        .noneMatch(a -> "CRITICAL".equals(a.get("severity"))));
    }

    @Override
    public Mono<Map<String, Object>> getCapacityPlanning() {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            Map<String, Object> planning = new HashMap<>();

            List<GpuNode> nodes = gpuNodeMapper.selectList(null);
            int totalCapacity = nodes.stream()
                    .mapToInt(n -> n.getTotalGpuCount() != null ? n.getTotalGpuCount() : 0)
                    .sum();

            LambdaQueryWrapper<GpuTask> pendingWrapper = new LambdaQueryWrapper<>();
            pendingWrapper.eq(GpuTask::getStatus, "PENDING");
            int pendingDemand = Math.toIntExact(gpuTaskMapper.selectCount(pendingWrapper));

            LambdaQueryWrapper<GpuTask> runningWrapper = new LambdaQueryWrapper<>();
            runningWrapper.eq(GpuTask::getStatus, "RUNNING");
            int runningDemand = Math.toIntExact(gpuTaskMapper.selectCount(runningWrapper));

            planning.put("totalGpuCapacity", totalCapacity);
            planning.put("runningGpuDemand", runningDemand);
            planning.put("pendingGpuDemand", pendingDemand);
            planning.put("availableCapacity", totalCapacity - runningDemand);
            planning.put("saturationPercent", totalCapacity > 0 ?
                    (double) runningDemand / totalCapacity * 100 : 0);
            planning.put("backlog", Math.max(0, pendingDemand - (totalCapacity - runningDemand)));
            planning.put("estimatedWaitMinutes", pendingDemand > 0 && totalCapacity > 0 ?
                    (int) Math.ceil((double) pendingDemand / totalCapacity * 5) : 0);

            return planning;
        });
    }

    @Override
    public Mono<List<String>> getOfflineNodes() {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            wrapper.ne(GpuNode::getStatus, "ONLINE");
            return gpuNodeMapper.selectList(wrapper).stream()
                    .map(GpuNode::getNodeId)
                    .collect(Collectors.toList());
        });
    }
}
