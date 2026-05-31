package com.monitoring.profiler.service;

import com.monitoring.common.utils.IdGenerator;
import com.monitoring.profiler.generator.FlameGraphGenerator;
import com.monitoring.profiler.model.FlameGraph;
import com.monitoring.profiler.model.ProfileSample;
import com.monitoring.profiler.sampler.CpuSampler;
import com.monitoring.profiler.sampler.MemorySampler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilingService {

    private final CpuSampler cpuSampler;
    private final MemorySampler memorySampler;
    private final FlameGraphGenerator flameGraphGenerator;

    private final Map<String, ProfileSession> activeSessions = new ConcurrentHashMap<>();

    public Mono<String> startProfiling(String type, long durationMs, long intervalMs) {
        return Mono.fromSupplier(() -> {
            String sessionId = "prof_" + IdGenerator.generateShortId();

            ProfileSession session = ProfileSession.builder()
                    .sessionId(sessionId)
                    .type(type)
                    .startTime(Instant.now())
                    .durationMs(durationMs)
                    .intervalMs(intervalMs)
                    .status("running")
                    .build();

            activeSessions.put(sessionId, session);

            if ("cpu".equalsIgnoreCase(type) || "all".equalsIgnoreCase(type)) {
                cpuSampler.startSampling(intervalMs, durationMs);
            }
            if ("memory".equalsIgnoreCase(type) || "all".equalsIgnoreCase(type)) {
                memorySampler.startSampling(intervalMs, durationMs);
            }

            log.info("Started profiling session: id={}, type={}, duration={}ms", sessionId, type, durationMs);
            return sessionId;
        });
    }

    public Mono<ProfileSession> getSession(String sessionId) {
        return Mono.fromSupplier(() -> activeSessions.get(sessionId));
    }

    public Mono<List<ProfileSample>> getSamples(String sessionId, String type) {
        return Mono.fromSupplier(() -> {
            if ("cpu".equalsIgnoreCase(type)) {
                return cpuSampler.getSamples();
            } else if ("memory".equalsIgnoreCase(type)) {
                return memorySampler.getSamples();
            }
            List<ProfileSample> all = new java.util.ArrayList<>(cpuSampler.getSamples());
            all.addAll(memorySampler.getSamples());
            return all;
        });
    }

    public Mono<FlameGraph> generateFlameGraph(String sessionId) {
        return getSamples(sessionId, "cpu")
                .map(flameGraphGenerator::generate);
    }

    public Mono<FlameGraph> compareSessions(String sessionId1, String sessionId2) {
        return Mono.zip(
                getSamples(sessionId1, "cpu").map(flameGraphGenerator::generate),
                getSamples(sessionId2, "cpu").map(flameGraphGenerator::generate),
                flameGraphGenerator::generateDiff
        );
    }

    public Mono<Void> stopProfiling(String sessionId) {
        return Mono.fromRunnable(() -> {
            cpuSampler.stopSampling();
            memorySampler.stopSampling();

            ProfileSession session = activeSessions.get(sessionId);
            if (session != null) {
                session.setStatus("completed");
                session.setEndTime(Instant.now());
            }

            log.info("Stopped profiling session: id={}", sessionId);
        });
    }

    public Mono<Map<String, Object>> getMemoryStats() {
        return Mono.fromSupplier(() -> {
            Runtime runtime = Runtime.getRuntime();
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalMemory", runtime.totalMemory());
            stats.put("freeMemory", runtime.freeMemory());
            stats.put("maxMemory", runtime.maxMemory());
            stats.put("usedMemory", runtime.totalMemory() - runtime.freeMemory());
            return stats;
        });
    }

    public void cleanOldSessions() {
        Instant threshold = Instant.now().minus(Duration.ofHours(1));
        activeSessions.entrySet().removeIf(entry ->
                entry.getValue().getEndTime() != null &&
                        entry.getValue().getEndTime().isBefore(threshold)
        );
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProfileSession {
        private String sessionId;
        private String type;
        private Instant startTime;
        private Instant endTime;
        private long durationMs;
        private long intervalMs;
        private String status;
    }
}
