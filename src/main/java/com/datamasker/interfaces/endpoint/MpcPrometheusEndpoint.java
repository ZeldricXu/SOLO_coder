package com.datamasker.interfaces.endpoint;

import com.datamasker.domain.mpc.monitor.MpcExecutionTracer;
import com.datamasker.domain.mpc.monitor.MpcMetrics;
import com.datamasker.domain.mpc.monitor.MpcStatusExposer;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Endpoint(id = "mpc-metrics")
@RequiredArgsConstructor
public class MpcPrometheusEndpoint {

    private final MpcMetrics mpcMetrics;
    private final MpcExecutionTracer executionTracer;
    private final MpcStatusExposer statusExposer;

    @ReadOperation
    public MpcMetricsDetail mpcMetrics() {
        MpcMetricsDetail detail = new MpcMetricsDetail();
        detail.setActiveSessions(mpcMetrics.getActiveSessions());
        detail.setTotalCreated(mpcMetrics.getTotalCreated());
        detail.setTotalCompleted(mpcMetrics.getTotalCompleted());
        detail.setTotalFailed(mpcMetrics.getTotalFailed());
        detail.setTotalTimedOut(mpcMetrics.getTotalTimedOut());
        detail.setAvgCreationLatencyMs(mpcMetrics.getAvgCreationLatency());
        detail.setAvgComputationLatencyMs(mpcMetrics.getAvgComputationLatency());
        detail.setSlowOperations(executionTracer.getSlowTraces(1000));
        detail.setActiveSessionDetails(statusExposer.getAllActiveSessions());
        return detail;
    }

    @Data
    public static class MpcMetricsDetail {
        private int activeSessions;
        private long totalCreated;
        private long totalCompleted;
        private long totalFailed;
        private long totalTimedOut;
        private double avgCreationLatencyMs;
        private double avgComputationLatencyMs;
        private List<MpcExecutionTracer.TraceSpan> slowOperations;
        private List<MpcStatusExposer.SessionSnapshot> activeSessionDetails;
    }
}
