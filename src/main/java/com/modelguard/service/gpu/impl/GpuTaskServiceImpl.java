package com.modelguard.service.gpu.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.PageResult;
import com.modelguard.converter.EntityConverter;
import com.modelguard.dto.request.GpuTaskSubmitRequest;
import com.modelguard.dto.response.GpuTaskResponse;
import com.modelguard.entity.GpuTask;
import com.modelguard.exception.BusinessException;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.mapper.GpuTaskMapper;
import com.modelguard.service.gpu.GpuTaskService;
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
public class GpuTaskServiceImpl implements GpuTaskService {

    private final GpuTaskMapper gpuTaskMapper;

    private static final List<String> VALID_STATUSES = Arrays.asList("PENDING", "SCHEDULED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED", "PREEMPTED");
    private static final List<Integer> VALID_PRIORITIES = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTaskResponse> submitTask(GpuTaskSubmitRequest request) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            GpuTask task = EntityConverter.toEntity(request);
            task.setTaskId(IdGeneratorUtil.generateGpuTaskId());
            task.setStatus("PENDING");
            task.setSubmittedAt(LocalDateTime.now());

            if (task.getPriority() == null) {
                task.setPriority(5);
            }
            if (!VALID_PRIORITIES.contains(task.getPriority())) {
                throw new BusinessException("Invalid priority: " + task.getPriority() + ", must be 0-9");
            }

            gpuTaskMapper.insert(task);
            log.info("Submitted GPU task: taskId={}, priority={}", task.getTaskId(), task.getPriority());
            return EntityConverter.toResponse(task);
        });
    }

    @Override
    public Mono<GpuTaskResponse> getTask(String taskId) {
        return getTaskEntity(taskId)
                .map(EntityConverter::toResponse);
    }

    @Override
    public Mono<GpuTask> getTaskEntity(String taskId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuTask::getTaskId, taskId);
            GpuTask task = gpuTaskMapper.selectOne(wrapper);
            if (task == null) {
                throw new ResourceNotFoundException("GpuTask", taskId);
            }
            return task;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTaskResponse> updateTaskStatus(String taskId, String status, Map<String, Object> progress) {
        if (!VALID_STATUSES.contains(status)) {
            throw new BusinessException("Invalid task status: " + status);
        }

        return getTaskEntity(taskId)
                .flatMap(task -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    task.setStatus(status);

                    if ("RUNNING".equals(status) && task.getStartedAt() == null) {
                        task.setStartedAt(LocalDateTime.now());
                    }
                    if (("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) && task.getCompletedAt() == null) {
                        task.setCompletedAt(LocalDateTime.now());
                    }

                    if (progress != null) {
                        task.setProgressData(progress);
                        if (progress.get("progress") instanceof Number) {
                            task.setProgress(((Number) progress.get("progress")).intValue());
                        }
                    }

                    gpuTaskMapper.updateById(task);
                    log.debug("Updated GPU task status: taskId={}, status={}", taskId, status);
                    return EntityConverter.toResponse(task);
                }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTaskResponse> assignTaskToNode(String taskId, String nodeId, Integer gpuIndex) {
        return getTaskEntity(taskId)
                .flatMap(task -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    task.setNodeId(nodeId);
                    task.setGpuIndex(gpuIndex);
                    task.setStatus("SCHEDULED");
                    task.setScheduledAt(LocalDateTime.now());

                    gpuTaskMapper.updateById(task);
                    log.info("Assigned GPU task: taskId={} to node={}, gpu={}", taskId, nodeId, gpuIndex);
                    return EntityConverter.toResponse(task);
                }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTaskResponse> markTaskCompleted(String taskId, Map<String, Object> result) {
        return getTaskEntity(taskId)
                .flatMap(task -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    task.setStatus("COMPLETED");
                    task.setProgress(100);
                    task.setCompletedAt(LocalDateTime.now());
                    task.setResultData(result);

                    gpuTaskMapper.updateById(task);
                    log.info("Completed GPU task: taskId={}", taskId);
                    return EntityConverter.toResponse(task);
                }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTaskResponse> markTaskFailed(String taskId, String errorMessage) {
        return getTaskEntity(taskId)
                .flatMap(task -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    task.setStatus("FAILED");
                    task.setErrorMessage(errorMessage);
                    task.setCompletedAt(LocalDateTime.now());

                    gpuTaskMapper.updateById(task);
                    log.error("GPU task failed: taskId={}, error={}", taskId, errorMessage);
                    return EntityConverter.toResponse(task);
                }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Boolean> cancelTask(String taskId) {
        return getTaskEntity(taskId)
                .flatMap(task -> {
                    if ("RUNNING".equals(task.getStatus()) || "SCHEDULED".equals(task.getStatus()) || "PENDING".equals(task.getStatus())) {
                        return ReactiveBridgeUtil.monoFromCallable(() -> {
                            task.setStatus("CANCELLED");
                            task.setCompletedAt(LocalDateTime.now());
                            gpuTaskMapper.updateById(task);
                            log.info("Cancelled GPU task: taskId={}", taskId);
                            return true;
                        });
                    }
                    return Mono.just(false);
                });
    }

    @Override
    public Mono<List<GpuTaskResponse>> listTasksByNode(String nodeId, String status) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuTask::getNodeId, nodeId);
            if (status != null && !status.isEmpty()) {
                wrapper.eq(GpuTask::getStatus, status);
            }
            wrapper.orderByDesc(GpuTask::getSubmittedAt);
            return gpuTaskMapper.selectList(wrapper).stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    public Mono<PageResult<GpuTaskResponse>> pageTasks(String status, Integer priority, int pageNum, int pageSize) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
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

            List<GpuTaskResponse> responses = result.getRecords().stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());

            return PageResult.of(responses, result.getTotal(), pageNum, pageSize);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTaskResponse> increaseTaskPriority(String taskId) {
        return getTaskEntity(taskId)
                .flatMap(task -> {
                    if (task.getPriority() >= 9) {
                        return Mono.just(EntityConverter.toResponse(task));
                    }
                    return ReactiveBridgeUtil.monoFromCallable(() -> {
                        task.setPriority(task.getPriority() + 1);
                        gpuTaskMapper.updateById(task);
                        log.info("Increased task priority: taskId={}, newPriority={}", taskId, task.getPriority());
                        return EntityConverter.toResponse(task);
                    });
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTaskResponse> decreaseTaskPriority(String taskId) {
        return getTaskEntity(taskId)
                .flatMap(task -> {
                    if (task.getPriority() <= 0) {
                        return Mono.just(EntityConverter.toResponse(task));
                    }
                    return ReactiveBridgeUtil.monoFromCallable(() -> {
                        task.setPriority(task.getPriority() - 1);
                        gpuTaskMapper.updateById(task);
                        log.info("Decreased task priority: taskId={}, newPriority={}", taskId, task.getPriority());
                        return EntityConverter.toResponse(task);
                    });
                });
    }

    @Override
    public Mono<Boolean> isTaskPreemptible(String taskId) {
        return getTaskEntity(taskId)
                .map(task -> {
                    if (!"RUNNING".equals(task.getStatus())) return false;
                    if (task.getPreemptible() == null) return false;
                    return task.getPreemptible() && task.getPriority() <= 3;
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<GpuTaskResponse> markTaskPreempted(String taskId, String preemptedByTaskId) {
        return getTaskEntity(taskId)
                .flatMap(task -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    task.setStatus("PREEMPTED");
                    task.setPreemptedBy(preemptedByTaskId);
                    task.setCompletedAt(LocalDateTime.now());

                    gpuTaskMapper.updateById(task);
                    log.info("Preempted GPU task: taskId={}, by={}", taskId, preemptedByTaskId);
                    return EntityConverter.toResponse(task);
                }));
    }

    @Override
    public Mono<List<GpuTaskResponse>> getPendingTasksByPriority() {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuTask::getStatus, "PENDING")
                    .orderByDesc(GpuTask::getPriority)
                    .orderByAsc(GpuTask::getSubmittedAt)
                    .last("LIMIT 100");
            return gpuTaskMapper.selectList(wrapper).stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    public Mono<Long> countTasksByStatus(String status) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GpuTask::getStatus, status);
            return gpuTaskMapper.selectCount(wrapper);
        });
    }
}
