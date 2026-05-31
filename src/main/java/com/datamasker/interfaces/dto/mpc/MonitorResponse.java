package com.datamasker.interfaces.dto.mpc;

import com.datamasker.domain.mpc.monitor.MpcExecutionTracer;
import lombok.Data;

import java.util.List;

@Data
public class MonitorResponse {

    private int activeSessions;
    private long totalCreated;
    private long totalCompleted;
    private long totalFailed;
    private long totalTimedOut;
    private double avgCreationLatency;
    private double avgComputationLatency;
    private List<MpcExecutionTracer.TraceSpan> slowOperations;
}
