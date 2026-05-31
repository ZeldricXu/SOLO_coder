package com.observability.profiling.service;

import com.observability.profiling.model.ProfileResult;
import com.observability.profiling.sampler.CPUSampler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilingService {

    private final CPUSampler cpuSampler;

    public Mono<ProfileResult> startCPUProfile(int durationMs, int intervalMs) {
        return Mono.fromCallable(() -> {
            int safeDuration = Math.min(durationMs, 60000);
            int safeInterval = Math.max(intervalMs, 10);
            log.info("Starting CPU profile - duration: {}ms, interval: {}ms", safeDuration, safeInterval);
            return cpuSampler.startProfile(safeDuration, safeInterval);
        });
    }

    public Mono<ProfileResult> getProfile(String profileId) {
        return Mono.fromCallable(() -> {
            ProfileResult result = cpuSampler.getProfile(profileId);
            if (result == null) {
                throw new RuntimeException("Profile not found: " + profileId);
            }
            return result;
        });
    }

    public Mono<List<ProfileResult>> listProfiles() {
        return Mono.fromCallable(cpuSampler::listProfiles);
    }

    public Mono<Map<String, Object>> getSystemStats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();

            Runtime runtime = Runtime.getRuntime();
            stats.put("jvm", Map.of(
                    "totalMemory", runtime.totalMemory(),
                    "freeMemory", runtime.freeMemory(),
                    "maxMemory", runtime.maxMemory(),
                    "usedMemory", runtime.totalMemory() - runtime.freeMemory(),
                    "availableProcessors", runtime.availableProcessors()
            ));

            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean)
                            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            stats.put("system", Map.of(
                    "processCpuLoad", osBean.getProcessCpuLoad(),
                    "systemCpuLoad", osBean.getSystemCpuLoad(),
                    "totalMemorySize", osBean.getTotalMemorySize(),
                    "freeMemorySize", osBean.getFreeMemorySize()
            ));

            return stats;
        });
    }

    public Mono<ProfileResult> compareProfiles(String profileId1, String profileId2) {
        return Mono.fromCallable(() -> {
            ProfileResult p1 = cpuSampler.getProfile(profileId1);
            ProfileResult p2 = cpuSampler.getProfile(profileId2);

            if (p1 == null || p2 == null) {
                throw new RuntimeException("One or both profiles not found");
            }

            ProfileResult diff = new ProfileResult();
            diff.setProfileId("diff_" + profileId1 + "_" + profileId2);
            diff.setType("diff");
            diff.setStartTime(p1.getStartTime());
            diff.setEndTime(p2.getEndTime());

            log.info("Profile comparison completed - profile1: {}, profile2: {}", profileId1, profileId2);
            return diff;
        });
    }
}
