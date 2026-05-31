package com.solocoder.platform.gpu.service;

import com.solocoder.platform.gpu.model.GpuResource;
import com.solocoder.platform.gpu.model.GpuTask;

import java.util.List;
import java.util.Optional;

public interface GpuTaskSchedulingService {

    GpuTask submitTask(GpuTask task);

    Optional<GpuTask> getTask(String taskId);

    List<GpuTask> listTasks();

    boolean cancelTask(String taskId);

    GpuResource registerGpu(GpuResource gpu);

    List<GpuResource> listGpus();

    void triggerScheduling();
}
