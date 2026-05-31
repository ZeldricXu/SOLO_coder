package com.solocoder.platform.gpu.scheduler;

import com.solocoder.platform.gpu.model.GpuTask;

import java.util.Optional;

public interface PreemptionStrategy {

    Optional<GpuTask> findPreemptionCandidate(GpuTask incomingTask);

    GpuTask preempt(GpuTask candidate);
}
