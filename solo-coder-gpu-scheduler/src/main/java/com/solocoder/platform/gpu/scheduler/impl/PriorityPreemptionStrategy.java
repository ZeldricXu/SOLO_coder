package com.solocoder.platform.gpu.scheduler.impl;

import com.solocoder.platform.gpu.model.GpuResource;
import com.solocoder.platform.gpu.model.GpuTask;
import com.solocoder.platform.gpu.scheduler.GpuResourceAllocator;
import com.solocoder.platform.gpu.scheduler.PreemptionStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriorityPreemptionStrategy implements PreemptionStrategy {

    private final GpuResourceAllocator resourceAllocator;

    @Override
    public Optional<GpuTask> findPreemptionCandidate(GpuTask incomingTask) {
        List<GpuResource> gpus = resourceAllocator.listGpus();
        GpuTask bestCandidate = null;
        int bestCandidatePriority = Integer.MAX_VALUE;

        for (GpuResource gpu : gpus) {
            if (gpu.getStatus() == GpuResource.GpuStatus.OFFLINE || gpu.getStatus() == GpuResource.GpuStatus.MAINTENANCE) {
                continue;
            }
            int potentialFreeMemory = gpu.getAvailableMemoryMb();
            if (potentialFreeMemory >= incomingTask.getRequiredMemoryMb()) {
                continue;
            }

            List<GpuTask> runningOnGpu = getRunningTasks(gpu.getGpuId());
            for (GpuTask running : runningOnGpu) {
                if (!running.isPreemptible()) continue;
                if (running.getPriority() >= incomingTask.getPriority()) continue;
                potentialFreeMemory += running.getRequiredMemoryMb();
                if (running.getPriority() < bestCandidatePriority) {
                    bestCandidate = running;
                    bestCandidatePriority = running.getPriority();
                }
                if (potentialFreeMemory >= incomingTask.getRequiredMemoryMb()) {
                    break;
                }
            }
        }

        if (bestCandidate != null) {
            log.info("Preemption candidate found: candidateTaskId={}, candidatePriority={}, incomingPriority={}",
                    bestCandidate.getTaskId(), bestCandidatePriority, incomingTask.getPriority());
        }
        return Optional.ofNullable(bestCandidate);
    }

    @Override
    public GpuTask preempt(GpuTask candidate) {
        resourceAllocator.release(candidate.getAssignedGpuId(), candidate.getTaskId());
        GpuTask preempted = GpuTask.builder()
                .taskId(candidate.getTaskId())
                .taskName(candidate.getTaskName())
                .requiredMemoryMb(candidate.getRequiredMemoryMb())
                .requiredCudaCores(candidate.getRequiredCudaCores())
                .priority(candidate.getPriority())
                .status(GpuTask.TaskStatus.PREEMPTED)
                .submittedAt(candidate.getSubmittedAt())
                .metadata(candidate.getMetadata())
                .preemptible(true)
                .build();
        log.info("Task preempted: taskId={}, gpuId={}", candidate.getTaskId(), candidate.getAssignedGpuId());
        return preempted;
    }

    private List<GpuTask> getRunningTasks(String gpuId) {
        return List.of();
    }
}
