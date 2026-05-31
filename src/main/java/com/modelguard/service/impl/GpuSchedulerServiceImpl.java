package com.modelguard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.PageResult;
import com.modelguard.config.MetricsConfig;
import com.modelguard.dto.GpuNodeDTO;
import com.modelguard.dto.GpuTaskSubmitDTO;
import com.modelguard.dto.HeartbeatDTO;
import com.modelguard.entity.GpuNode;
import com.modelguard.entity.GpuTask;
import com.modelguard.exception.BusinessException;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.mapper.GpuNodeMapper;
import com.modelguard.mapper.GpuTaskMapper;
import com.modelguard.service.GpuSchedulerService;
import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GpuSchedulerServiceImpl implements GpuSchedulerService {

    private final GpuNodeMapper gpuNodeMapper;
    private final GpuTaskMapper gpuTaskMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuNode> registerNode(GpuNodeDTO dto) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuNode::getHostname, dto.getHostname());
            if (gpuNodeMapper.selectCount(wrapper) > 0) {
                throw new BusinessException("节点已存在: " + dto.getHostname());
            }

            GpuNode node = new GpuNode();
            node.setNodeId("gpunode_" + IdUtil.simpleUUID());
            node.setHostname(dto.getHostname());
            node.setIpAddress(dto.getIpAddress());
            node.setGpuCount(dto.getGpuCount());
            node.setGpuModel(dto.getGpuModel());
            node.setTotalGpuMemoryGb(dto.getTotalGpuMemoryGb());
            node.setAvailableGpuMemoryGb(dto.getTotalGpuMemoryGb());
            node.setStatus("ONLINE");
            node.setLabels(dto.getLabels());
            node.setLastHeartbeat(LocalDateTime.now());

            gpuNodeMapper.insert(node);
            log.info("Registered GPU node: nodeId={}, hostname={}", node.getNodeId(), dto.getHostname());
            return node;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<GpuNode> getNode(String nodeId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuNode::getNodeId, nodeId);
            GpuNode node = gpuNodeMapper.selectOne(wrapper);
            if (node == null) {
                throw new ResourceNotFoundException("GpuNode", nodeId);
            }
            return node;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<GpuNode>> listNodes(String status) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(GpuNode::getStatus, status);
            }
            wrapper.orderByAsc(GpuNode::getCreatedAt);
            return gpuNodeMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuNode> updateNodeStatus(String nodeId, String status) {
        return getNode(nodeId)
                .flatMap(node -> Mono.fromCallable(() -> {
                    node.setStatus(status);
                    gpuNodeMapper.updateById(node);
                    log.info("Updated GPU node status: nodeId={}, status={}", nodeId, status);
                    return node;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuNode> processHeartbeat(HeartbeatDTO dto) {
        return getNode(dto.getNodeId())
                .flatMap(node -> Mono.fromCallable(() -> {
                    node.setAvailableGpuMemoryGb(dto.getAvailableGpuMemoryGb());
                    node.setLastHeartbeat(LocalDateTime.now());
                    if ("OFFLINE".equals(node.getStatus())) {
                        node.setStatus("ONLINE");
                    }
                    gpuNodeMapper.updateById(node);
                    return node;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> removeNode(String nodeId) {
        return getNode(nodeId)
                .flatMap(node -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<GpuTask> taskWrapper = new LambdaQueryWrapper<>();
                    taskWrapper.eq(GpuTask::getNodeId, nodeId)
                            .eq(GpuTask::getStatus, "RUNNING");
                    List<GpuTask> runningTasks = gpuTaskMapper.selectList(taskWrapper);
                    for (GpuTask task : runningTasks) {
                        task.setStatus("PENDING");
                        task.setNodeId(null);
                        task.setGpuIndices(null);
                        task.setScheduledAt(null);
                        task.setStartedAt(null);
                        gpuTaskMapper.updateById(task);
                        log.info("Moved running task back to pending: taskId={}", task.getTaskId());
                    }

                    gpuNodeMapper.deleteById(node.getId());
                    log.info("Removed GPU node: nodeId={}", nodeId);
                    return null;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTask> submitTask(GpuTaskSubmitDTO dto) {
        return Mono.fromCallable(() -> {
            GpuTask task = new GpuTask();
            task.setTaskId("gputask_" + IdUtil.simpleUUID());
            task.setName(dto.getName());
            task.setTaskType(dto.getTaskType());
            task.setPriority(dto.getPriority());
            task.setRequiredGpuMemoryGb(dto.getRequiredGpuMemoryGb());
            task.setGpuCount(dto.getGpuCount());
            task.setStatus("PENDING");
            task.setPreemptible(dto.getPreemptible());
            task.setCommand(dto.getCommand());
            task.setParameters(dto.getParameters());
            task.setSubmittedBy(dto.getSubmittedBy());
            task.setSubmittedAt(LocalDateTime.now());

            gpuTaskMapper.insert(task);
            MetricsConfig.gpuTaskSubmittedCounter.increment();
            log.info("Submitted GPU task: taskId={}, priority={}, memory={}GB",
                    task.getTaskId(), dto.getPriority(), dto.getRequiredGpuMemoryGb());
            return task;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<GpuTask> getTask(String taskId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuTask::getTaskId, taskId);
            GpuTask task = gpuTaskMapper.selectOne(wrapper);
            if (task == null) {
                throw new ResourceNotFoundException("GpuTask", taskId);
            }
            return task;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<PageResult<GpuTask>> pageTasks(String status, Integer priority, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            Page<GpuTask> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(GpuTask::getStatus, status);
            }
            if (priority != null) {
                wrapper.eq(GpuTask::getPriority, priority);
            }
            wrapper.orderByDesc(GpuTask::getPriority)
                    .orderByAsc(GpuTask::getSubmittedAt);
            Page<GpuTask> result = gpuTaskMapper.selectPage(page, wrapper);
            return PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTask> scheduleTask() {
        return getPendingQueue()
                .flatMap(pendingTasks -> {
                    if (pendingTasks.isEmpty()) {
                        return Mono.empty();
                    }
                    return listNodes("ONLINE")
                            .flatMap(nodes -> {
                                for (GpuTask task : pendingTasks) {
                                    GpuNode selectedNode = null;
                                    for (GpuNode node : nodes) {
                                        if (node.getAvailableGpuMemoryGb().compareTo(task.getRequiredGpuMemoryGb()) >= 0) {
                                            selectedNode = node;
                                            break;
                                        }
                                    }

                                    if (selectedNode == null && task.getPriority() >= 8) {
                                        selectedNode = tryPreempt(task, nodes);
                                    }

                                    if (selectedNode != null) {
                                        return allocateTaskToNode(task, selectedNode);
                                    }
                                }
                                return Mono.empty();
                            });
                });
    }

    private GpuNode tryPreempt(GpuTask highPriorityTask, List<GpuNode> nodes) {
        for (GpuNode node : nodes) {
            BigDecimal totalMemory = node.getTotalGpuMemoryGb();
            BigDecimal usedMemory = totalMemory.subtract(node.getAvailableGpuMemoryGb());
            if (usedMemory.compareTo(highPriorityTask.getRequiredGpuMemoryGb()) >= 0) {
                return node;
            }
        }
        return null;
    }

    private Mono<GpuTask> allocateTaskToNode(GpuTask task, GpuNode node) {
        return Mono.fromCallable(() -> {
            BigDecimal requiredMem = task.getRequiredGpuMemoryGb();
            BigDecimal availableMem = node.getAvailableGpuMemoryGb();

            if (availableMem.compareTo(requiredMem) < 0) {
                LambdaQueryWrapper<GpuTask> runningWrapper = new LambdaQueryWrapper<>();
                runningWrapper.eq(GpuTask::getNodeId, node.getNodeId())
                        .eq(GpuTask::getStatus, "RUNNING")
                        .eq(GpuTask::getPreemptible, true)
                        .orderByAsc(GpuTask::getPriority);
                List<GpuTask> preemptibleTasks = gpuTaskMapper.selectList(runningWrapper);

                BigDecimal freedMemory = BigDecimal.ZERO;
                List<GpuTask> toPreempt = new ArrayList<>();
                for (GpuTask pt : preemptibleTasks) {
                    if (freedMemory.add(availableMem).compareTo(requiredMem) >= 0) {
                        break;
                    }
                    toPreempt.add(pt);
                    freedMemory = freedMemory.add(pt.getRequiredGpuMemoryGb());
                }

                if (freedMemory.add(availableMem).compareTo(requiredMem) < 0) {
                    return null;
                }

                for (GpuTask pt : toPreempt) {
                    pt.setStatus("PENDING");
                    pt.setPreemptedBy(task.getTaskId());
                    pt.setNodeId(null);
                    pt.setGpuIndices(null);
                    pt.setScheduledAt(null);
                    pt.setStartedAt(null);
                    gpuTaskMapper.updateById(pt);
                    log.info("Preempted task: taskId={} by highPriorityTask={}", pt.getTaskId(), task.getTaskId());
                }

                node.setAvailableGpuMemoryGb(node.getAvailableGpuMemoryGb().add(freedMemory));
            }

            String gpuIndices = allocateGpuIndices(node, task.getGpuCount());

            task.setStatus("SCHEDULED");
            task.setNodeId(node.getNodeId());
            task.setGpuIndices(gpuIndices);
            task.setScheduledAt(LocalDateTime.now());
            gpuTaskMapper.updateById(task);

            node.setAvailableGpuMemoryGb(node.getAvailableGpuMemoryGb().subtract(requiredMem));
            gpuNodeMapper.updateById(node);

            log.info("Scheduled GPU task: taskId={}, nodeId={}, gpus={}",
                    task.getTaskId(), node.getNodeId(), gpuIndices);
            return task;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String allocateGpuIndices(GpuNode node, int gpuCount) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < gpuCount && i < node.getGpuCount(); i++) {
            indices.add(i);
        }
        return indices.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTask> startTask(String taskId, String nodeId, String gpuIndices) {
        return getTask(taskId)
                .flatMap(task -> {
                    if (!"SCHEDULED".equals(task.getStatus())) {
                        throw new BusinessException("任务不在SCHEDULED状态，无法启动，当前状态: " + task.getStatus());
                    }
                    task.setStatus("RUNNING");
                    task.setStartedAt(LocalDateTime.now());
                    return Mono.fromCallable(() -> {
                        gpuTaskMapper.updateById(task);
                        log.info("Started GPU task: taskId={}", taskId);
                        return task;
                    }).subscribeOn(Schedulers.boundedElastic());
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTask> completeTask(String taskId) {
        return getTask(taskId)
                .flatMap(task -> {
                    String nodeId = task.getNodeId();
                    BigDecimal memory = task.getRequiredGpuMemoryGb();

                    task.setStatus("COMPLETED");
                    task.setCompletedAt(LocalDateTime.now());

                    return getNode(nodeId)
                            .flatMap(node -> Mono.fromCallable(() -> {
                                node.setAvailableGpuMemoryGb(node.getAvailableGpuMemoryGb().add(memory));
                                gpuNodeMapper.updateById(node);
                                gpuTaskMapper.updateById(task);
                                MetricsConfig.gpuTaskCompletedCounter.increment();
                                log.info("Completed GPU task: taskId={}", taskId);
                                return task;
                            }).subscribeOn(Schedulers.boundedElastic()));
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTask> failTask(String taskId, String errorDetail) {
        return getTask(taskId)
                .flatMap(task -> {
                    String nodeId = task.getNodeId();
                    BigDecimal memory = task.getRequiredGpuMemoryGb();

                    task.setStatus("FAILED");
                    task.setErrorDetail(errorDetail);
                    task.setCompletedAt(LocalDateTime.now());

                    return getNode(nodeId)
                            .flatMap(node -> Mono.fromCallable(() -> {
                                node.setAvailableGpuMemoryGb(node.getAvailableGpuMemoryGb().add(memory));
                                gpuNodeMapper.updateById(node);
                                gpuTaskMapper.updateById(task);
                                MetricsConfig.gpuTaskCompletedCounter.increment();
                                log.error("GPU task failed: taskId={}, error={}", taskId, errorDetail);
                                return task;
                            }).subscribeOn(Schedulers.boundedElastic()))
                            .switchIfEmpty(Mono.fromCallable(() -> {
                                gpuTaskMapper.updateById(task);
                                return task;
                            }).subscribeOn(Schedulers.boundedElastic()));
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTask> cancelTask(String taskId) {
        return getTask(taskId)
                .flatMap(task -> {
                    if ("COMPLETED".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
                        throw new BusinessException("任务已结束，无法取消");
                    }

                    String nodeId = task.getNodeId();
                    BigDecimal memory = task.getRequiredGpuMemoryGb();
                    boolean wasRunning = "RUNNING".equals(task.getStatus()) || "SCHEDULED".equals(task.getStatus());

                    task.setStatus("CANCELLED");
                    task.setCompletedAt(LocalDateTime.now());

                    if (wasRunning && nodeId != null) {
                        return getNode(nodeId)
                                .flatMap(node -> Mono.fromCallable(() -> {
                                    node.setAvailableGpuMemoryGb(node.getAvailableGpuMemoryGb().add(memory));
                                    gpuNodeMapper.updateById(node);
                                    gpuTaskMapper.updateById(task);
                                    log.info("Cancelled GPU task: taskId={}", taskId);
                                    return task;
                                }).subscribeOn(Schedulers.boundedElastic()));
                    } else {
                        return Mono.fromCallable(() -> {
                            gpuTaskMapper.updateById(task);
                            return task;
                        }).subscribeOn(Schedulers.boundedElastic());
                    }
                });
    }

    @Override
    public Mono<Boolean> preemptTask(String taskId, String highPriorityTaskId) {
        return getTask(taskId)
                .flatMap(task -> {
                    if (!task.getPreemptible()) {
                        return Mono.just(false);
                    }
                    if (!"RUNNING".equals(task.getStatus())) {
                        return Mono.just(false);
                    }

                    String nodeId = task.getNodeId();
                    BigDecimal memory = task.getRequiredGpuMemoryGb();

                    task.setStatus("PENDING");
                    task.setPreemptedBy(highPriorityTaskId);
                    task.setNodeId(null);
                    task.setGpuIndices(null);
                    task.setScheduledAt(null);
                    task.setStartedAt(null);

                    return getNode(nodeId)
                            .flatMap(node -> Mono.fromCallable(() -> {
                                node.setAvailableGpuMemoryGb(node.getAvailableGpuMemoryGb().add(memory));
                                gpuNodeMapper.updateById(node);
                                gpuTaskMapper.updateById(task);
                                log.info("Preempted task: taskId={} by {}", taskId, highPriorityTaskId);
                                return true;
                            }).subscribeOn(Schedulers.boundedElastic()));
                });
    }

    @Override
    public Mono<Map<String, Object>> getClusterStatus() {
        return Mono.zip(listNodes(null), getPendingQueue(),
                listNodes("ONLINE").flatMap(nodes -> {
                    List<String> nodeIds = nodes.stream().map(GpuNode::getNodeId).collect(Collectors.toList());
                    if (nodeIds.isEmpty()) {
                        return Mono.just(Collections.<GpuTask>emptyList());
                    }
                    return Mono.fromCallable(() -> {
                        LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
                        wrapper.in(GpuTask::getNodeId, nodeIds)
                                .eq(GpuTask::getStatus, "RUNNING");
                        return gpuTaskMapper.selectList(wrapper);
                    }).subscribeOn(Schedulers.boundedElastic());
                }))
                .map(tuple -> {
                    List<GpuNode> allNodes = tuple.getT1();
                    List<GpuTask> pendingTasks = tuple.getT2();
                    List<GpuTask> runningTasks = tuple.getT3();

                    Map<String, Object> status = new LinkedHashMap<>();
                    status.put("totalNodes", allNodes.size());
                    status.put("onlineNodes", allNodes.stream().filter(n -> "ONLINE".equals(n.getStatus())).count());
                    status.put("offlineNodes", allNodes.stream().filter(n -> "OFFLINE".equals(n.getStatus())).count());
                    status.put("totalGpuCount", allNodes.stream().mapToInt(GpuNode::getGpuCount).sum());
                    status.put("totalMemoryGb", allNodes.stream()
                            .map(GpuNode::getTotalGpuMemoryGb)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
                    status.put("availableMemoryGb", allNodes.stream()
                            .map(GpuNode::getAvailableGpuMemoryGb)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
                    status.put("pendingTasks", pendingTasks.size());
                    status.put("runningTasks", runningTasks.size());

                    Map<String, Object> usage = new LinkedHashMap<>();
                    BigDecimal totalMem = allNodes.stream()
                            .map(GpuNode::getTotalGpuMemoryGb)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal availMem = allNodes.stream()
                            .map(GpuNode::getAvailableGpuMemoryGb)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (totalMem.compareTo(BigDecimal.ZERO) > 0) {
                        usage.put("memoryUsagePercent", totalMem.subtract(availMem)
                                .multiply(BigDecimal.valueOf(100))
                                .divide(totalMem, 2, BigDecimal.ROUND_HALF_UP));
                    }
                    status.put("usage", usage);

                    return status;
                });
    }

    @Override
    public Mono<List<GpuTask>> getPendingQueue() {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuTask::getStatus, "PENDING")
                    .orderByDesc(GpuTask::getPriority)
                    .orderByAsc(GpuTask::getSubmittedAt);
            return gpuTaskMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<GpuTask>> getNodeRunningTasks(String nodeId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuTask::getNodeId, nodeId)
                    .eq(GpuTask::getStatus, "RUNNING")
                    .orderByDesc(GpuTask::getPriority);
            return gpuTaskMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, Object>> getTaskStatus(String taskId) {
        return getTask(taskId)
                .map(task -> {
                    Map<String, Object> status = new LinkedHashMap<>();
                    status.put("taskId", task.getTaskId());
                    status.put("name", task.getName());
                    status.put("status", task.getStatus());
                    status.put("priority", task.getPriority());
                    status.put("nodeId", task.getNodeId());
                    status.put("gpuIndices", task.getGpuIndices());
                    status.put("submittedAt", task.getSubmittedAt());
                    status.put("scheduledAt", task.getScheduledAt());
                    status.put("startedAt", task.getStartedAt());
                    status.put("completedAt", task.getCompletedAt());
                    status.put("errorDetail", task.getErrorDetail());
                    status.put("preemptedBy", task.getPreemptedBy());

                    if (task.getSubmittedAt() != null) {
                        LocalDateTime end = task.getCompletedAt() != null ? task.getCompletedAt() : LocalDateTime.now();
                        long duration = java.time.Duration.between(task.getSubmittedAt(), end).getSeconds();
                        status.put("durationSeconds", duration);
                    }

                    return status;
                });
    }

    @Scheduled(fixedDelay = 5000)
    public void schedulingLoop() {
        scheduleTask().subscribe(
                task -> log.debug("Auto-scheduled task: {}", task.getTaskId()),
                error -> log.error("Scheduling error", error)
        );
    }

    @Scheduled(fixedDelay = 60000)
    public void checkNodeHealth() {
        Mono.fromCallable(() -> {
            LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuNode::getStatus, "ONLINE")
                    .lt(GpuNode::getLastHeartbeat, threshold);
            List<GpuNode> staleNodes = gpuNodeMapper.selectList(wrapper);

            for (GpuNode node : staleNodes) {
                node.setStatus("OFFLINE");
                gpuNodeMapper.updateById(node);
                log.warn("Marked node as offline due to stale heartbeat: nodeId={}", node.getNodeId());

                LambdaQueryWrapper<GpuTask> taskWrapper = new LambdaQueryWrapper<>();
                taskWrapper.eq(GpuTask::getNodeId, node.getNodeId())
                        .eq(GpuTask::getStatus, "RUNNING");
                List<GpuTask> runningTasks = gpuTaskMapper.selectList(taskWrapper);
                for (GpuTask task : runningTasks) {
                    task.setStatus("PENDING");
                    task.setNodeId(null);
                    task.setGpuIndices(null);
                    task.setScheduledAt(null);
                    task.setStartedAt(null);
                    gpuTaskMapper.updateById(task);
                    log.info("Moved task back to pending due to node offline: taskId={}", task.getTaskId());
                }
            }
            return staleNodes.size();
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }
}
