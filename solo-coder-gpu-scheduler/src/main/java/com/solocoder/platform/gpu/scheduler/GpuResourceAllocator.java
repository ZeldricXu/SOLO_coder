package com.solocoder.platform.gpu.scheduler;

import com.solocoder.platform.gpu.model.GpuResource;
import com.solocoder.platform.gpu.model.GpuTask;

import java.util.List;
import java.util.Optional;

public interface GpuResourceAllocator {

    Optional<GpuResource> findAvailableGpu(GpuTask task);

    boolean allocate(String gpuId, GpuTask task);

    void release(String gpuId, String taskId);

    void registerGpu(GpuResource gpu);

    void removeGpu(String gpuId);

    List<GpuResource> listGpus();

    Optional<GpuResource> getGpu(String gpuId);
}
