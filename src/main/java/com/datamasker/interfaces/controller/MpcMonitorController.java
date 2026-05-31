package com.datamasker.interfaces.controller;

import com.datamasker.domain.mpc.monitor.MpcExecutionTracer;
import com.datamasker.domain.mpc.monitor.MpcMetrics;
import com.datamasker.domain.mpc.monitor.MpcStatusExposer;
import com.datamasker.interfaces.dto.Result;
import com.datamasker.interfaces.dto.mpc.MonitorResponse;
import com.datamasker.interfaces.dto.mpc.SessionTraceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mpc/monitor")
@RequiredArgsConstructor
public class MpcMonitorController {

    private final MpcMetrics mpcMetrics;
    private final MpcExecutionTracer executionTracer;
    private final MpcStatusExposer statusExposer;

    @GetMapping("/metrics")
    public Result<MonitorResponse> getMetrics() {
        MonitorResponse response = new MonitorResponse();
        response.setActiveSessions(mpcMetrics.getActiveSessions());
        response.setTotalCreated(mpcMetrics.getTotalCreated());
        response.setTotalCompleted(mpcMetrics.getTotalCompleted());
        response.setTotalFailed(mpcMetrics.getTotalFailed());
        response.setTotalTimedOut(mpcMetrics.getTotalTimedOut());
        response.setAvgCreationLatency(mpcMetrics.getAvgCreationLatency());
        response.setAvgComputationLatency(mpcMetrics.getAvgComputationLatency());
        response.setSlowOperations(executionTracer.getSlowTraces(1000));
        return Result.success(response);
    }

    @GetMapping("/sessions/active")
    public Result<List<MpcStatusExposer.SessionSnapshot>> getActiveSessions() {
        return Result.success(statusExposer.getAllActiveSessions());
    }

    @GetMapping("/trace/{sessionId}")
    public Result<SessionTraceResponse> getSessionTrace(@PathVariable String sessionId) {
        List<MpcExecutionTracer.TraceSpan> spans = executionTracer.getTrace(sessionId);
        SessionTraceResponse response = new SessionTraceResponse();
        response.setSessionId(sessionId);
        response.setSpans(spans.stream().map(this::toDto).toList());
        return Result.success(response);
    }

    @GetMapping("/slow")
    public Result<List<MpcExecutionTracer.TraceSpan>> getSlowOperations(
            @RequestParam(defaultValue = "1000") int thresholdMs) {
        return Result.success(executionTracer.getSlowTraces(thresholdMs));
    }

    private SessionTraceResponse.TraceSpanDto toDto(MpcExecutionTracer.TraceSpan span) {
        SessionTraceResponse.TraceSpanDto dto = new SessionTraceResponse.TraceSpanDto();
        dto.setSpanId(span.getSpanId());
        dto.setOperation(span.getOperation());
        dto.setStartTime(span.getStartTime());
        dto.setDurationMs(span.getDurationMs());
        dto.setSuccess(span.isSuccess());
        return dto;
    }
}
