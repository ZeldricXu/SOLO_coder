package com.observability.profiling.sampler;

import com.observability.common.util.IdGenerator;
import com.observability.profiling.model.ProfileResult;
import com.observability.profiling.model.StackFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class CPUSampler {

    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private final Map<String, ProfileResult> activeProfiles = new ConcurrentHashMap<>();

    public ProfileResult startProfile(int durationMs, int intervalMs) {
        String profileId = IdGenerator.generateId("prof");

        ProfileResult result = new ProfileResult();
        result.setProfileId(profileId);
        result.setType("cpu");
        result.setStartTime(LocalDateTime.now());
        result.setStatus("running");

        activeProfiles.put(profileId, result);

        Thread sampleThread = new Thread(() -> runSampling(profileId, durationMs, intervalMs));
        sampleThread.setDaemon(true);
        sampleThread.start();

        return result;
    }

    private void runSampling(String profileId, int durationMs, int intervalMs) {
        Map<String, Long> frameSamples = new HashMap<>();
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < durationMs) {
            try {
                long[] threadIds = threadMXBean.getAllThreadIds();
                ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds, Integer.MAX_VALUE);

                for (ThreadInfo info : threadInfos) {
                    if (info == null) continue;
                    StackTraceElement[] stackTrace = info.getStackTrace();
                    if (stackTrace.length > 0) {
                        StringBuilder keyBuilder = new StringBuilder();
                        for (int i = 0; i < Math.min(stackTrace.length, 30); i++) {
                            StackTraceElement elem = stackTrace[i];
                            keyBuilder.append(elem.getClassName()).append(".")
                                    .append(elem.getMethodName());
                            if (i < stackTrace.length - 1) {
                                keyBuilder.append(";");
                            }
                        }
                        String key = keyBuilder.toString();
                        frameSamples.merge(key, 1L, Long::sum);
                    }
                }

                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Sampling error", e);
            }
        }

        completeProfile(profileId, frameSamples, durationMs);
    }

    private void completeProfile(String profileId, Map<String, Long> frameSamples, long durationMs) {
        ProfileResult result = activeProfiles.get(profileId);
        if (result == null) return;

        long totalSamples = frameSamples.values().stream().mapToLong(Long::longValue).sum();

        List<StackFrame> frames = new ArrayList<>();
        for (Map.Entry<String, Long> entry : frameSamples.entrySet()) {
            String[] parts = entry.getKey().split(";");
            if (parts.length > 0) {
                String topFrame = parts[0];
                int dotIdx = topFrame.lastIndexOf('.');
                String className = dotIdx > 0 ? topFrame.substring(0, dotIdx) : topFrame;
                String methodName = dotIdx > 0 ? topFrame.substring(dotIdx + 1) : topFrame;

                StackFrame frame = new StackFrame();
                frame.setClassName(className);
                frame.setMethod(methodName);
                frame.setSamples(entry.getValue());
                frame.setTotalSamples(totalSamples);
                frame.setPercentage(totalSamples > 0 ? (entry.getValue() * 100.0 / totalSamples) : 0);
                frames.add(frame);
            }
        }

        frames.sort((a, b) -> Long.compare(b.getSamples(), a.getSamples()));

        result.setStackFrames(frames);
        result.setEndTime(LocalDateTime.now());
        result.setDurationMs(durationMs);
        result.setStatus("completed");
        result.setFlameGraphData(generateFlameGraphData(frames));

        log.info("Profile completed - profileId: {}, duration: {}ms, samples: {}",
                profileId, durationMs, totalSamples);
    }

    private String generateFlameGraphData(List<StackFrame> frames) {
        StringBuilder flameGraph = new StringBuilder();
        flameGraph.append("flamegraph\n");
        for (StackFrame frame : frames) {
            flameGraph.append(frame.getClassName()).append(".")
                    .append(frame.getMethod()).append(" ")
                    .append(frame.getSamples()).append("\n");
        }
        return flameGraph.toString();
    }

    public ProfileResult getProfile(String profileId) {
        return activeProfiles.get(profileId);
    }

    public List<ProfileResult> listProfiles() {
        return new ArrayList<>(activeProfiles.values());
    }
}
