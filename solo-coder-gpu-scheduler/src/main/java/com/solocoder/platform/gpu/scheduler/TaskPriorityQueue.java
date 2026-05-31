package com.solocoder.platform.gpu.scheduler;

import com.solocoder.platform.gpu.model.GpuTask;

import java.util.List;
import java.util.Optional;

public interface TaskPriorityQueue {

    void enqueue(GpuTask task);

    Optional<GpuTask> dequeue();

    Optional<GpuTask> peek();

    List<GpuTask> listPending();

    boolean remove(String taskId);

    int size();

    void updatePriority(String taskId, int newPriority);
}
