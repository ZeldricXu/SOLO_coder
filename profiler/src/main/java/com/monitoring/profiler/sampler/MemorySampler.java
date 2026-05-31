package com.monitoring.profiler.sampler;

import com.monitoring.common.utils.IdGenerator;
import com.monitoring.profiler.model.ProfileSample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class MemorySampler {

    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final List<MemoryPoolMXBean> memoryPoolMXBeans = ManagementFactory.getMemoryPoolMXBeans();
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
                    sampleMemory();
                    sampleCount++;
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Memory sampling error", e);
                }
            }

            sampling.set(false);
            log.info("Memory sampling completed: {} samples collected", sampleCount);
        }, "memory-sampler");

        samplingThread.setDaemon(true);
        samplingThread.start();
    }

    private void sampleMemory() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("heapUsed", heapUsage.getUsed());
        metadata.put("heapCommitted", heapUsage.getCommitted());
        metadata.put("heapMax", heapUsage.getMax());
        metadata.put("nonHeapUsed", nonHeapUsage.getUsed());
        metadata.put("nonHeapCommitted", nonHeapUsage.getCommitted());

        Map<String, MemoryUsage> poolUsage = new HashMap<>();
        for (MemoryPoolMXBean pool : memoryPoolMXBeans) {
            poolUsage.put(pool.getName(), pool.getUsage());
        }
        metadata.put("memoryPools", poolUsage);

        ProfileSample sample = ProfileSample.builder()
                .profileId("mem_" + IdGenerator.generateShortId())
                .type("memory")
                .timestamp(Instant.now())
                .metadata(metadata)
                .sampleCount(1)
                .stackTrace(new ArrayList<>())
                .build();

        synchronized (samples) {
            samples.add(sample);
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
