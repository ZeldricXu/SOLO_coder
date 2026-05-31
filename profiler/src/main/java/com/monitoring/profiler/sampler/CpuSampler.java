package com.monitoring.profiler.sampler;

import com.monitoring.common.utils.IdGenerator;
import com.monitoring.profiler.model.ProfileSample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class CpuSampler {

    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private final AtomicBoolean sampling = new AtomicBoolean(false);
    private Thread samplingThread;
    private final List<ProfileSample> samples = new ArrayList<>();

    public void startSampling(long intervalMs, long durationMs) {
        if (sampling.getAndSet(true)) {
            return;
        }

        samplingThread = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            int sampleCount = 0;

            while (sampling.get() && (System.currentTimeMillis() - startTime) < durationMs) {
                try {
                    sampleAllThreads();
                    sampleCount++;
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("CPU sampling error", e);
                }
            }

            sampling.set(false);
            log.info("CPU sampling completed: {} samples collected", sampleCount);
        }, "cpu-sampler");

        samplingThread.setDaemon(true);
        samplingThread.start();
    }

    private void sampleAllThreads() {
        long[] threadIds = threadMXBean.getAllThreadIds();
        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds, Integer.MAX_VALUE);

        for (ThreadInfo threadInfo : threadInfos) {
            if (threadInfo == null || threadInfo.getThreadState() == Thread.State.TERMINATED) {
                continue;
            }

            StackTraceElement[] stackTrace = threadInfo.getStackTrace();
            if (stackTrace.length == 0) {
                continue;
            }

            List<ProfileSample.StackFrame> frames = new ArrayList<>();
            for (int i = 0; i < Math.min(stackTrace.length, 128); i++) {
                StackTraceElement element = stackTrace[i];
                frames.add(ProfileSample.StackFrame.builder()
                        .className(element.getClassName())
                        .methodName(element.getMethodName())
                        .fileName(element.getFileName())
                        .lineNumber(element.getLineNumber())
                        .samples(1L)
                        .build());
            }

            ProfileSample sample = ProfileSample.builder()
                    .profileId("cpu_" + IdGenerator.generateShortId())
                    .type("cpu")
                    .timestamp(Instant.now())
                    .threadName(threadInfo.getThreadName())
                    .threadId(threadInfo.getThreadId())
                    .stackTrace(frames)
                    .sampleCount(1)
                    .build();

            synchronized (samples) {
                samples.add(sample);
            }
        }
    }

    public List<ProfileSample> getSamples() {
        synchronized (samples) {
            return new ArrayList<>(samples);
        }
    }

    public void clearSamples() {
        synchronized (samples) {
            samples.clear();
        }
    }

    public void stopSampling() {
        sampling.set(false);
        if (samplingThread != null) {
            samplingThread.interrupt();
        }
    }

    public boolean isSampling() {
        return sampling.get();
    }
}
