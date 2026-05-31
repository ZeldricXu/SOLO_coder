package com.solocoder.platform.gpu.scheduler.impl;

import com.solocoder.platform.gpu.model.GpuResource;
import com.solocoder.platform.gpu.model.GpuTask;
import com.solocoder.platform.gpu.scheduler.GpuResourceAllocator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class GpuResourceAllocatorImpl implements GpuResourceAllocator {

    private final Map<String, GpuResource> gpuRegistry = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> gpuAllocations = new ConcurrentHashMap<>();

    @Override
    public Optional<GpuResource> findAvailableGpu(GpuTask task) {
        return gpuRegistry.values().stream()
                .filter(gpu -> gpu.getStatus() == GpuResource.GpuStatus.AVAILABLE || gpu.getStatus() == GpuResource.GpuStatus.BUSY)
                .filter(gpu -> gpu.getAvailableMemoryMb() >= task.getRequiredMemoryMb())
                .filter(gpu -> gpu.getCudaCores() >= task.getRequiredCudaCores())
                .min(Comparator.comparingDouble(GpuResource::getUtilizationPercent)
                        .thenComparingInt(GpuResource::getAvailableMemoryMb).reversed());
    }

    @Override
    public boolean allocate(String gpuId, GpuTask task) {
        GpuResource gpu = gpuRegistry.get(gpuId);
        if (gpu == null) {
            log.warn("GPU not found: {}", gpuId);
            return false;
        }
        if (gpu.getAvailableMemoryMb() < task.getRequiredMemoryMb()) {
            log.warn("Insufficient GPU memory: gpuId={}, required={}, available={}",
                    gpuId, task.getRequiredMemoryMb(), gpu.getAvailableMemoryMb());
            return false;
        }

        gpu.setUsedMemoryMb(gpu.getUsedMemoryMb() + task.getRequiredMemoryMb());
        gpu.setUtilizationPercent((double) gpu.getUsedMemoryMb() / gpu.getTotalMemoryMb() * 100);
        gpu.setStatus(gpu.getAvailableMemoryMb() > 0 ? GpuResource.GpuStatus.BUSY : GpuResource.GpuStatus.BUSY);
        gpuAllocations.computeIfAbsent(gpuId, k -> ConcurrentHashMap.newKeySet()).add(task.getTaskId());

        log.info("GPU allocated: gpuId={}, taskId={}, memory={}MB, utilization={}%",
                gpuId, task.getTaskId(), task.getRequiredMemoryMb(), gpu.getUtilizationPercent());
        return true;
    }

    @Override
    public void release(String gpuId, String taskId) {
        GpuResource gpu = gpuRegistry.get(gpuId);
        if (gpu == null) return;

        Set<String> allocations = gpuAllocations.get(gpuId);
        if (allocations != null && allocations.remove(taskId)) {
            int releaseMemory = 0;
            if (gpu.getUsedMemoryMb() > 0) {
                releaseMemory = Math.min(gpu.getUsedMemoryMb(), gpu.getTotalMemoryMb() / Math.max(1, allocations.size() + 1));
                gpu.setUsedMemoryMb(Math.max(0, gpu.getUsedMemoryMb() - releaseMemory));
            }
            gpu.setUtilizationPercent((double) gpu.getUsedMemoryMb() / gpu.getTotalMemoryMb() * 100);
            if (allocations.isEmpty()) {
                gpu.setStatus(GpuResource.GpuStatus.AVAILABLE);
            }
        }

        log.info("GPU released: gpuId={}, taskId={}", gpuId, taskId);
    }

    @Override
    public void registerGpu(GpuResource gpu) {
        gpuRegistry.put(gpu.getGpuId(), gpu);
        log.info("GPU registered: id={}, name={}, memory={}MB", gpu.getGpuId(), gpu.getName(), gpu.getTotalMemoryMb());
    }

    @Override
    public void removeGpu(String gpuId) {
        gpuRegistry.remove(gpuId);
        gpuAllocations.remove(gpuId);
        log.info("GPU removed: id={}", gpuId);
    }

    @Override
    public List<GpuResource> listGpus() {
        return new ArrayList<>(gpuRegistry.values());
    }

    @Override
    public Optional<GpuResource> getGpu(String gpuId) {
        return Optional.ofNullable(gpuRegistry.get(gpuId));
    }
}
