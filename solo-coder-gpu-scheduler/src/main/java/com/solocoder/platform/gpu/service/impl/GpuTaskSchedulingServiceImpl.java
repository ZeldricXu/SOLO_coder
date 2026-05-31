package com.solocoder.platform.gpu.service.impl;

import com.solocoder.platform.gpu.model.GpuResource;
import com.solocoder.platform.gpu.model.GpuTask;
import com.solocoder.platform.gpu.scheduler.GpuResourceAllocator;
import com.solocoder.platform.gpu.scheduler.PreemptionStrategy;
import com.solocoder.platform.gpu.scheduler.TaskPriorityQueue;
import com.solocoder.platform.gpu.service.GpuTaskSchedulingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GpuTaskSchedulingServiceImpl implements GpuTaskSchedulingService {

    private final TaskPriorityQueue taskQueue;
    private final GpuResourceAllocator resourceAllocator;
    private final PreemptionStrategy preemptionStrategy;
    private final Map<String, GpuTask> taskStore = new ConcurrentHashMap<>();

    @Override
    public GpuTask submitTask(GpuTask task) {
        String taskId = task.getTaskId() != null ? task.getTaskId() : UUID.randomUUID().toString();
        GpuTask queuedTask = GpuTask.builder()
                .taskId(taskId)
                .taskName(task.getTaskName())
                .requiredMemoryMb(task.getRequiredMemoryMb())
                .requiredCudaCores(task.getRequiredCudaCores())
                .priority(task.getPriority())
                .status(GpuTask.TaskStatus.QUEUED)
                .submittedAt(LocalDateTime.now())
                .metadata(task.getMetadata())
                .preemptible(task.isPreemptible())
                .build();

        taskStore.put(taskId, queuedTask);
        taskQueue.enqueue(queuedTask);
        triggerScheduling();
        return queuedTask;
    }

    @Override
    public Optional<GpuTask> getTask(String taskId) {
        return Optional.ofNullable(taskStore.get(taskId));
    }

    @Override
    public List<GpuTask> listTasks() {
        return new ArrayList<>(taskStore.values());
    }

    @Override
    public boolean cancelTask(String taskId) {
        GpuTask task = taskStore.get(taskId);
        if (task == null) return false;
        if (task.getStatus() == GpuTask.TaskStatus.RUNNING && task.getAssignedGpuId() != null) {
            resourceAllocator.release(task.getAssignedGpuId(), taskId);
        }
        taskQueue.remove(taskId);
        taskStore.put(taskId, GpuTask.builder()
                .taskId(task.getTaskId())
                .taskName(task.getTaskName())
                .requiredMemoryMb(task.getRequiredMemoryMb())
                .requiredCudaCores(task.getRequiredCudaCores())
                .priority(task.getPriority())
                .status(GpuTask.TaskStatus.FAILED)
                .submittedAt(task.getSubmittedAt())
                .metadata(task.getMetadata())
                .preemptible(task.isPreemptible())
                .errorMessage("Cancelled by user")
                .build());
        return true;
    }

    @Override
    public GpuResource registerGpu(GpuResource gpu) {
        resourceAllocator.registerGpu(gpu);
        return gpu;
    }

    @Override
    public List<GpuResource> listGpus() {
        return resourceAllocator.listGpus();
    }

    @Override
    public void triggerScheduling() {
        while (!taskQueue.isEmpty()) {
            Optional<GpuTask> taskOpt = taskQueue.peek();
            if (taskOpt.isEmpty()) break;

            GpuTask task = taskOpt.get();
            Optional<GpuResource> gpuOpt = resourceAllocator.findAvailableGpu(task);

            if (gpuOpt.isPresent()) {
                GpuResource gpu = gpuOpt.get();
                taskQueue.dequeue();
                boolean allocated = resourceAllocator.allocate(gpu.getGpuId(), task);
                if (allocated) {
                    GpuTask running = GpuTask.builder()
                            .taskId(task.getTaskId())
                            .taskName(task.getTaskName())
                            .requiredMemoryMb(task.getRequiredMemoryMb())
                            .requiredCudaCores(task.getRequiredCudaCores())
                            .priority(task.getPriority())
                            .status(GpuTask.TaskStatus.RUNNING)
                            .assignedGpuId(gpu.getGpuId())
                            .submittedAt(task.getSubmittedAt())
                            .startedAt(LocalDateTime.now())
                            .metadata(task.getMetadata())
                            .preemptible(task.isPreemptible())
                            .build();
                    taskStore.put(task.getTaskId(), running);
                    log.info("Task scheduled: id={}, gpu={}", task.getTaskId(), gpu.getGpuId());
                } else {
                    break;
                }
            } else {
                Optional<GpuTask> candidate = preemptionStrategy.findPreemptionCandidate(task);
                if (candidate.isPresent()) {
                    GpuTask preempted = preemptionStrategy.preempt(candidate.get());
                    taskStore.put(preempted.getTaskId(), preempted);
                    taskQueue.enqueue(preempted);
                    continue;
                }
                break;
            }
        }
    }
}
