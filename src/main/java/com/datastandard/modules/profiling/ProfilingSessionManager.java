package com.datastandard.modules.profiling;

import cn.hutool.core.util.StrUtil;
import com.datastandard.modules.profiling.dto.FlameGraphDiff;
import com.datastandard.modules.profiling.dto.ProfilingReport;
import com.datastandard.modules.profiling.dto.ProfilingRequest;
import com.datastandard.modules.profiling.entity.ProfilingSession;
import com.datastandard.modules.profiling.mapper.ProfilingSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilingSessionManager {

    private final ProfilingSessionMapper sessionMapper;
    private final CpuSampler cpuSampler;
    private final MemorySampler memorySampler;
    private final AsyncProfilerBridge asyncProfilerBridge;
    private final FlameGraphGenerator flameGraphGenerator;
    private final FlameGraphComparator flameGraphComparator;
    private final ProfilingResultExporter resultExporter;

    private final Map<String, ActiveSession> activeSessions = new HashMap<>();
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(2);
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public void init() {
        if (initialized.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::checkSessions, 1, 1, TimeUnit.SECONDS);
            log.info("ProfilingSessionManager initialized");
        }
    }

    public Mono<ProfilingSession> startSession(ProfilingRequest request) {
        return Mono.fromCallable(() -> {
            String sessionId = UUID.randomUUID().toString();
            String pid = StrUtil.isNotBlank(request.getTargetJvmPid()) ?
                    request.getTargetJvmPid() : asyncProfilerBridge.getCurrentJvmPid().orElse("unknown");

            String included = request.getIncludedPackages() != null ?
                    String.join(",", request.getIncludedPackages()) : null;
            String excluded = request.getExcludedPackages() != null ?
                    String.join(",", request.getExcludedPackages()) : null;

            ProfilingSession session = ProfilingSession.builder()
                    .sessionId(sessionId)
                    .sessionName(request.getSessionName())
                    .description(request.getDescription())
                    .status("STARTING")
                    .startTime(Instant.now())
                    .requestedDuration(request.getDuration())
                    .samplingIntervalMs(request.getSamplingIntervalMs())
                    .cpuProfiling(request.isCpuProfiling())
                    .memoryProfiling(request.isMemoryProfiling())
                    .lockProfiling(request.isLockProfiling())
                    .allocationProfiling(request.isAllocationProfiling())
                    .includedPackages(included)
                    .excludedPackages(excluded)
                    .targetJvmPid(pid)
                    .compareWithSessionId(request.getCompareWithSessionId())
                    .jvmVersion(asyncProfilerBridge.getJvmVersion())
                    .createdBy(request.getCreatedBy())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .deleted(0)
                    .build();

            sessionMapper.insert(session);

            ActiveSession activeSession = new ActiveSession();
            activeSession.session = session;
            activeSession.request = request;
            activeSession.startTime = Instant.now();
            activeSession.useAsyncProfiler = asyncProfilerBridge.isAvailable();

            try {
                if (activeSession.useAsyncProfiler) {
                    AsyncProfilerBridge.ProfilerResult result = asyncProfilerBridge.startProfiling(
                            sessionId, pid, request.getDuration(),
                            request.isCpuProfiling(), request.isMemoryProfiling(),
                            request.isLockProfiling(), request.isAllocationProfiling(),
                            request.getSamplingIntervalMs(),
                            request.getIncludedPackages() != null ? new ArrayList<>(request.getIncludedPackages()) : null,
                            request.getExcludedPackages() != null ? new ArrayList<>(request.getExcludedPackages()) : null
                    );
                    activeSession.profilerResult = result;
                } else {
                    if (request.isCpuProfiling()) {
                        cpuSampler.start(request.getSamplingIntervalMs());
                    }
                    if (request.isMemoryProfiling()) {
                        memorySampler.start(request.getSamplingIntervalMs() * 10);
                    }
                }

                session.setStatus("RUNNING");
                sessionMapper.updateById(session);
                activeSessions.put(sessionId, activeSession);

                log.info("Profiling session started: {} [{}]", sessionId, request.getSessionName());
                return session;
            } catch (Exception e) {
                session.setStatus("FAILED");
                session.setErrorMessage(e.getMessage());
                session.setEndTime(Instant.now());
                sessionMapper.updateById(session);
                throw new RuntimeException("Failed to start profiling session", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ProfilingSession> stopSession(String sessionId) {
        return Mono.fromCallable(() -> {
            ActiveSession activeSession = activeSessions.get(sessionId);
            if (activeSession == null) {
                return sessionMapper.findBySessionId(sessionId)
                        .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
            }

            try {
                Instant endTime = Instant.now();
                Duration actualDuration = Duration.between(activeSession.startTime, endTime);

                if (activeSession.profilerResult != null) {
                    activeSession.profilerResult.stop();
                }
                cpuSampler.stop();
                memorySampler.stop();

                ProfilingSession session = activeSession.session;
                session.setStatus("STOPPED");
                session.setEndTime(endTime);
                session.setActualDuration(actualDuration);
                session.setUpdatedAt(endTime);
                sessionMapper.updateSessionStatus(sessionId, "COMPLETED", endTime, actualDuration, endTime);

                ProfilingReport report = buildReport(sessionId, activeSession);

                if (activeSession.request.isGenerateFlameGraph()) {
                    try {
                        if (activeSession.profilerResult != null) {
                            report.setFlameGraphReport(flameGraphGenerator.generateFromJfr(
                                    activeSession.profilerResult.getJfrOutputFile(), sessionId));
                        } else {
                            report.setFlameGraphReport(flameGraphGenerator.generateFromStackTraces(
                                    cpuSampler.getStackTraceSnapshots(), sessionId));
                        }
                        session.setFlameGraphPath(report.getFlameGraphReport().getFilePath());
                    } catch (Exception e) {
                        log.warn("Failed to generate flame graph: {}", e.getMessage());
                    }
                }

                if (StrUtil.isNotBlank(session.getCompareWithSessionId())) {
                    try {
                        FlameGraphDiff diff = compareWithSession(
                                session.getCompareWithSessionId(), sessionId,
                                report.getFlameGraphReport() != null ? report.getFlameGraphReport().getSvgContent() : null);
                        report.getRecommendations().addAll(flameGraphComparator.generateRecommendations(diff));
                        resultExporter.exportDiff(diff, sessionId);
                        session.setDiffReportPath("/tmp/profiling/" + sessionId + "_diff.json");
                    } catch (Exception e) {
                        log.warn("Failed to compare sessions: {}", e.getMessage());
                    }
                }

                session.setCpuReportPath("/tmp/profiling/" + sessionId + "_cpu.json");
                session.setMemoryReportPath("/tmp/profiling/" + sessionId + "_memory.json");
                sessionMapper.updateReportPaths(sessionId, session.getFlameGraphPath(),
                        session.getCpuReportPath(), session.getMemoryReportPath(), endTime);

                if (activeSession.request.isAutoExport()) {
                    try {
                        resultExporter.exportReport(report);
                    } catch (IOException e) {
                        log.warn("Failed to export report: {}", e.getMessage());
                    }
                }

                activeSessions.remove(sessionId);
                log.info("Profiling session completed: {} [{}]", sessionId, session.getSessionName());
                return session;
            } catch (Exception e) {
                log.error("Failed to stop profiling session: {}", sessionId, e);
                sessionMapper.markAsFailed(sessionId, e.getMessage(), Instant.now(), Instant.now());
                throw new RuntimeException("Failed to stop profiling session", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ProfilingReport> getReport(String sessionId) {
        return Mono.fromCallable(() -> {
            ProfilingSession session = sessionMapper.findBySessionId(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

            ActiveSession activeSession = activeSessions.get(sessionId);
            if (activeSession != null) {
                ProfilingReport report = new ProfilingReport();
                report.setSessionId(sessionId);
                report.setSessionName(session.getSessionName());
                report.setStatus(session.getStatus());
                report.setStartTime(session.getStartTime());
                report.setCpuReport(cpuSampler.buildReport());
                report.setMemoryReport(memorySampler.buildReport());
                return report;
            }

            throw new IllegalStateException("Session is not active, report not available");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ProfilingSession> getSession(String sessionId) {
        return Mono.fromCallable(() -> sessionMapper.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<ProfilingSession>> listSessions(String status, int limit) {
        return Mono.fromCallable(() -> {
            if (StrUtil.isNotBlank(status)) {
                return sessionMapper.findByStatus(status);
            }
            return sessionMapper.findByTimeRange(Instant.now().minus(Duration.ofDays(7)), Instant.now());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void checkSessions() {
        for (Map.Entry<String, ActiveSession> entry : activeSessions.entrySet()) {
            ActiveSession session = entry.getValue();
            Duration elapsed = Duration.between(session.startTime, Instant.now());

            if (elapsed.compareTo(session.request.getDuration()) >= 0) {
                log.info("Auto-stopping profiling session: {}", entry.getKey());
                stopSession(entry.getKey()).subscribe();
            }
        }
    }

    private ProfilingReport buildReport(String sessionId, ActiveSession activeSession) {
        ProfilingSession session = activeSession.session;

        ProfilingReport report = ProfilingReport.builder()
                .sessionId(sessionId)
                .sessionName(session.getSessionName())
                .description(session.getDescription())
                .startTime(session.getStartTime())
                .endTime(session.getEndTime())
                .actualDuration(session.getActualDuration())
                .status(session.getStatus())
                .jvmPid(session.getTargetJvmPid())
                .jvmVersion(session.getJvmVersion())
                .createdBy(session.getCreatedBy())
                .createdAt(Instant.now())
                .recommendations(new ArrayList<>())
                .build();

        if (activeSession.request.isCpuProfiling()) {
            report.setCpuReport(cpuSampler.buildReport());
        }
        if (activeSession.request.isMemoryProfiling()) {
            report.setMemoryReport(memorySampler.buildReport());
        }

        if (report.getCpuReport() != null && report.getCpuReport().getMaxCpuUsage() > 90) {
            report.getRecommendations().add("CPU使用率峰值超过90%，建议检查是否存在CPU密集型任务或无限循环");
        }
        if (report.getMemoryReport() != null && report.getMemoryReport().getGcThroughput() < 95) {
            report.getRecommendations().add("GC吞吐量低于95%，建议检查内存分配模式或增加堆内存");
        }
        if (report.getMemoryReport() != null && report.getMemoryReport().getAverageHeapUsage() > 80) {
            report.getRecommendations().add("堆内存平均使用率超过80%，建议增加堆内存或优化内存使用");
        }

        return report;
    }

    private FlameGraphDiff compareWithSession(String baseSessionId, String targetSessionId,
                                               String targetSvg) throws IOException {
        Optional<ProfilingSession> baseSession = sessionMapper.findBySessionId(baseSessionId);
        if (baseSession.isEmpty() || baseSession.get().getFlameGraphPath() == null) {
            throw new IllegalArgumentException("Base session or its flame graph not found");
        }

        String baseSvg = asyncProfilerBridge.readFlameGraphSvg(baseSession.get().getFlameGraphPath());
        if (baseSvg == null) {
            throw new IllegalStateException("Cannot read base flame graph");
        }

        return flameGraphComparator.compare(baseSessionId, baseSvg, targetSessionId, targetSvg);
    }

    public void shutdown() {
        for (String sessionId : new ArrayList<>(activeSessions.keySet())) {
            stopSession(sessionId).block(Duration.ofSeconds(10));
        }
        scheduler.shutdown();
        cpuSampler.stop();
        memorySampler.stop();
        log.info("ProfilingSessionManager shutdown complete");
    }

    private static class ActiveSession {
        ProfilingSession session;
        ProfilingRequest request;
        Instant startTime;
        boolean useAsyncProfiler;
        AsyncProfilerBridge.ProfilerResult profilerResult;
    }
}
