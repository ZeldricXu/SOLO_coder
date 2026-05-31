package com.modelguard.service.gpu.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.modelguard.converter.EntityConverter;
import com.modelguard.dto.response.GpuTaskResponse;
import com.modelguard.entity.GpuNode;
import com.modelguard.entity.GpuTask;
import com.modelguard.mapper.GpuNodeMapper;
import com.modelguard.mapper.GpuTaskMapper;
import com.modelguard.service.gpu.GpuNodeService;
import com.modelguard.service.gpu.GpuSchedulerService;
import com.modelguard.service.gpu.GpuTaskService;
import com.modelguard.service.gpu.ResourceAllocationService;
import com.modelguard.util.ReactiveBridgeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class GpuSchedulerServiceImpl implements GpuSchedulerService {

    private final GpuTaskService gpuTaskService;
    private final GpuNodeService gpuNodeService;
    private final ResourceAllocationService resourceAllocationService;
    private final GpuTaskMapper gpuTaskMapper;
    private final GpuNodeMapper gpuNodeMapper;

    private final AtomicBoolean schedulerPaused = new AtomicBoolean(false);

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTaskResponse> scheduleNextTask() {
        if (schedulerPaused.get()) {
            return Mono.empty();
        }

        return getNextPendingTask()
                .flatMap(task -> {
                    if (task == null) {
                        return Mono.empty();
                    }
                    return scheduleTask(task);
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<List<GpuTaskResponse>> scheduleBatch(int batchSize) {
        if (schedulerPaused.get()) {
            return Mono.just(Collections.emptyList());
        }

        return getPendingTasks(batchSize)
                .flatMap(tasks -> {
                    List<Mono<GpuTaskResponse>> scheduleMonos = new ArrayList<>();
                    for (GpuTask task : tasks) {
                        scheduleMonos.add(scheduleTask(task).onErrorResume(e -> {
                            log.warn("Failed to schedule task {}: {}", task.getTaskId(), e.getMessage());
                            return Mono.empty();
                        }));
                    }
                    return Mono.zip(scheduleMonos, responses ->
                            Arrays.stream(responses)
                                    .filter(r -> r instanceof GpuTaskResponse)
                                    .map(r -> (GpuTaskResponse) r)
                                    .collect(java.util.stream.Collectors.toList())
                    );
                });
    }

    private Mono<GpuTask> getNextPendingTask() {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuTask::getStatus, "PENDING")
                    .orderByDesc(GpuTask::getPriority)
                    .orderByAsc(GpuTask::getSubmittedAt)
                    .last("LIMIT 1");
            return gpuTaskMapper.selectOne(wrapper);
        });
    }

    private Mono<List<GpuTask>> getPendingTasks(int limit) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuTask::getStatus, "PENDING")
                    .orderByDesc(GpuTask::getPriority)
                    .orderByAsc(GpuTask::getSubmittedAt)
                    .last("LIMIT " + limit);
            return gpuTaskMapper.selectList(wrapper);
        });
    }

    private Mono<GpuTaskResponse> scheduleTask(GpuTask task) {
        int requiredGpus = task.getGpuCount() != null ? task.getGpuCount() : 1;
        int requiredMemory = task.getMemoryGb() != null ? task.getMemoryGb() : 8;

        return getAvailableNodes(requiredGpus, requiredMemory)
                .flatMap(nodes -> {
                    if (nodes.isEmpty()) {
                        return checkAndHandlePreemption(task)
                                .flatMap(preempted -> {
                                    if (preempted) {
                                        return getAvailableNodes(requiredGpus, requiredMemory)
                                                .flatMap(newNodes -> {
                                                    if (newNodes.isEmpty()) {
                                                        return Mono.empty();
                                                    }
                                                    return allocateAndAssign(task, newNodes);
                                                });
                                    }
                                    return Mono.empty();
                                });
                    }
                    return allocateAndAssign(task, nodes);
                });
    }

    private Mono<List<GpuNode>> getAvailableNodes(int requiredGpus, int requiredMemory) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuNode::getStatus, "ONLINE")
                    .ge(GpuNode::getAvailableGpuCount, requiredGpus)
                    .ge(GpuNode::getAvailableMemoryGb, requiredGpus * requiredMemory)
                    .orderByDesc(GpuNode::getAvailableGpuCount);
            return gpuNodeMapper.selectList(wrapper);
        });
    }

    private Mono<GpuTaskResponse> allocateAndAssign(GpuTask task, List<GpuNode> nodes) {
        return resourceAllocationService.findBestFitNode(task, nodes)
                .flatMap(node -> {
                    if (node == null) {
                        return Mono.empty();
                    }
                    int requiredGpus = task.getGpuCount() != null ? task.getGpuCount() : 1;
                    int requiredMemory = task.getMemoryGb() != null ? task.getMemoryGb() : 8;

                    return resourceAllocationService.allocateGpus(node, requiredGpus, requiredMemory)
                            .flatMap(allocatedGpus -> {
                                Integer firstGpu = allocatedGpus.isEmpty() ? null : allocatedGpus.get(0);
                                return gpuTaskService.assignTaskToNode(task.getTaskId(), node.getNodeId(), firstGpu);
                            });
                });
    }

    @Override
    public Mono<Boolean> checkAndHandlePreemption(GpuTask highPriorityTask) {
        if (highPriorityTask.getPriority() == null || highPriorityTask.getPriority() < 7) {
            return Mono.just(false);
        }

        return findPreemptibleTask(highPriorityTask)
                .flatMap(preemptibleTask -> {
                    if (preemptibleTask == null) {
                        return Mono.just(false);
                    }
                    return preemptTask(preemptibleTask, highPriorityTask);
                });
    }

    @Override
    public Mono<GpuTask> findPreemptibleTask(GpuTask highPriorityTask) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            int requiredMemory = highPriorityTask.getMemoryGb() != null ?
                    highPriorityTask.getMemoryGb() * (highPriorityTask.getGpuCount() != null ? highPriorityTask.getGpuCount() : 1) : 8;

            LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuTask::getStatus, "RUNNING")
                    .eq(GpuTask::getPreemptible, true)
                    .le(GpuTask::getPriority, 3)
                    .ge(GpuTask::getMemoryGb, requiredMemory)
                    .orderByAsc(GpuTask::getPriority)
                    .orderByAsc(GpuTask::getStartedAt)
                    .last("LIMIT 1");

            return gpuTaskMapper.selectOne(wrapper);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Boolean> preemptTask(GpuTask runningTask, GpuTask highPriorityTask) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            String nodeId = runningTask.getNodeId();
            List<Integer> gpuIndices = runningTask.getGpuIndex() != null ?
                    Collections.singletonList(runningTask.getGpuIndex()) : Collections.emptyList();

            runningTask.setStatus("PREEMPTED");
            runningTask.setPreemptedBy(highPriorityTask.getTaskId());
            runningTask.setCompletedAt(LocalDateTime.now());
            gpuTaskMapper.updateById(runningTask);

            if (nodeId != null && !gpuIndices.isEmpty()) {
                LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(GpuNode::getNodeId, nodeId);
                GpuNode node = gpuNodeMapper.selectOne(wrapper);
                if (node != null) {
                    List<Integer> currentAllocated = node.getAllocatedGpus() != null ?
                            new ArrayList<>(node.getAllocatedGpus()) : new ArrayList<>();
                    currentAllocated.removeAll(gpuIndices);
                    node.setAllocatedGpus(currentAllocated);
                    node.setAvailableGpuCount(node.getAvailableGpuCount() + gpuIndices.size());
                    node.setAvailableMemoryGb(node.getAvailableMemoryGb() +
                            (runningTask.getMemoryGb() != null ? runningTask.getMemoryGb() : 8));
                    gpuNodeMapper.updateById(node);
                }
            }

            log.info("Preempted task {} for high priority task {}", runningTask.getTaskId(), highPriorityTask.getTaskId());
            return true;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Boolean> requeuePreemptedTask(GpuTask preemptedTask) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            preemptedTask.setStatus("PENDING");
            preemptedTask.setNodeId(null);
            preemptedTask.setGpuIndex(null);
            preemptedTask.setScheduledAt(null);
            preemptedTask.setStartedAt(null);
            preemptedTask.setCompletedAt(null);
            preemptedTask.setRetryCount((preemptedTask.getRetryCount() != null ? preemptedTask.getRetryCount() : 0) + 1);

            if (preemptedTask.getRetryCount() > 3) {
                preemptedTask.setPriority(Math.max(0, preemptedTask.getPriority() - 1));
            }

            gpuTaskMapper.updateById(preemptedTask);
            log.info("Requeued preempted task: {}", preemptedTask.getTaskId());
            return true;
        });
    }

    @Override
    public Mono<Map<String, Object>> getSchedulerStatus() {
        return Mono.zip(
                getPendingQueueSize(),
                getRunningTaskCount(),
                isSchedulerPaused()
        ).map(tuple -> {
            Map<String, Object> status = new HashMap<>();
            status.put("paused", tuple.getT3());
            status.put("pendingQueueSize", tuple.getT1());
            status.put("runningTaskCount", tuple.getT2());
            status.put("timestamp", System.currentTimeMillis());
            return status;
        });
    }

    @Override
    public Mono<Boolean> pauseScheduler() {
        schedulerPaused.set(true);
        log.info("GPU scheduler paused");
        return Mono.just(true);
    }

    @Override
    public Mono<Boolean> resumeScheduler() {
        schedulerPaused.set(false);
        log.info("GPU scheduler resumed");
        return Mono.just(true);
    }

    @Override
    public Mono<Boolean> isSchedulerPaused() {
        return Mono.just(schedulerPaused.get());
    }

    @Override
    public Mono<Integer> getPendingQueueSize() {
        return gpuTaskService.countTasksByStatus("PENDING")
                .map(Long::intValue);
    }

    @Override
    public Mono<Integer> getRunningTaskCount() {
        return gpuTaskService.countTasksByStatus("RUNNING")
                .map(Long::intValue);
    }

    @Override
    public Mono<Map<String, Object>> getQueueStats() {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();

            List<String> statuses = Arrays.asList("PENDING", "SCHEDULED", "RUNNING", "COMPLETED", "FAILED", "PREEMPTED");
            Map<String, Long> statusCounts = new HashMap<>();

            for (String status : statuses) {
                LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(GpuTask::getStatus, status);
                statusCounts.put(status.toLowerCase(), gpuTaskMapper.selectCount(wrapper));
            }
            stats.put("statusCounts", statusCounts);

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
            stats.put("priorityCounts", priorityCounts);

            LambdaQueryWrapper<GpuTask> oldestWrapper = new LambdaQueryWrapper<>();
            oldestWrapper.eq(GpuTask::getStatus, "PENDING")
                    .orderByAsc(GpuTask::getSubmittedAt)
                    .last("LIMIT 1");
            GpuTask oldestPending = gpuTaskMapper.selectOne(oldestWrapper);
            if (oldestPending != null) {
                stats.put("oldestPendingTask", oldestPending.getTaskId());
                stats.put("oldestPendingSubmittedAt", oldestPending.getSubmittedAt());
            }

            return stats;
        });
    }
}
