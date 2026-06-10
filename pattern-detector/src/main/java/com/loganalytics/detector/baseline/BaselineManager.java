package com.loganalytics.detector.baseline;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.loganalytics.common.model.LogPattern;
import com.loganalytics.detector.config.DetectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public class BaselineManager {
    private static final Logger log = LoggerFactory.getLogger(BaselineManager.class);

    private final DetectorConfig config;
    private final Map<String, PatternBaseline> baselineMap;
    private final Cache<String, double[]> statsCache;

    static class PatternBaseline {
        final String patternId;
        final int windowMinutes;
        final int historySlots;
        final AtomicLongArray historyCounts;
        final AtomicLong currentWindowCount;
        final AtomicLong lastUpdateTime;
        volatile int currentSlot;
        volatile double mean;
        volatile double stdDev;
        volatile boolean statsValid;

        PatternBaseline(String patternId, int windowMinutes, int historyDays) {
            this.patternId = patternId;
            this.windowMinutes = windowMinutes;
            this.historySlots = (int) (Duration.ofDays(historyDays).toMinutes() / windowMinutes);
            this.historyCounts = new AtomicLongArray(historySlots);
            this.currentWindowCount = new AtomicLong(0);
            this.lastUpdateTime = new AtomicLong(System.currentTimeMillis());
            this.currentSlot = 0;
        }

        void recordCount(long count) {
            historyCounts.set(currentSlot, count);
            currentSlot = (currentSlot + 1) % historySlots;
            currentWindowCount.set(0);
            statsValid = false;
        }

        void increment() {
            currentWindowCount.incrementAndGet();
            lastUpdateTime.set(System.currentTimeMillis());
        }

        void computeStats(int minPoints) {
            int nonZeroCount = 0;
            long sum = 0;
            long sumSq = 0;

            for (int i = 0; i < historySlots; i++) {
                long v = historyCounts.get(i);
                if (v > 0) {
                    sum += v;
                    sumSq += v * v;
                    nonZeroCount++;
                }
            }

            if (nonZeroCount >= minPoints) {
                mean = (double) sum / nonZeroCount;
                double variance = ((double) sumSq / nonZeroCount) - (mean * mean);
                stdDev = Math.sqrt(Math.max(variance, 0));
                statsValid = true;
            } else {
                mean = 0;
                stdDev = 0;
                statsValid = false;
            }
        }

        double getSigmaScore(long currentCount, double sigmaThreshold) {
            if (!statsValid) return 0.0;
            if (stdDev == 0) return currentCount > mean ? sigmaThreshold + 1 : 0.0;
            return (currentCount - mean) / stdDev;
        }
    }

    public BaselineManager(DetectorConfig config) {
        this.config = config;
        this.baselineMap = new ConcurrentHashMap<>();
        this.statsCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(Duration.ofMinutes(1))
                .build();
    }

    public void processPattern(LogPattern pattern) {
        PatternBaseline baseline = baselineMap.computeIfAbsent(
                pattern.getId(),
                id -> new PatternBaseline(id, config.getFrequencyWindowMinutes(), config.getBaselineHistoryDays())
        );
        baseline.increment();
    }

    public void rotateWindow() {
        long now = System.currentTimeMillis();
        long windowMs = Duration.ofMinutes(config.getFrequencyWindowMinutes()).toMillis();

        for (PatternBaseline baseline : baselineMap.values()) {
            long lastUpdate = baseline.lastUpdateTime.get();
            if (now - lastUpdate >= windowMs) {
                long count = baseline.currentWindowCount.get();
                baseline.recordCount(count);
                baseline.computeStats(config.getMinBaselinePoints());
                log.debug("Rotated window for pattern {}, count={}, mean={:.2f}, stdDev={:.2f}",
                        baseline.patternId, count, baseline.mean, baseline.stdDev);
            }
        }
    }

    public double getSigmaScore(String patternId, long currentCount) {
        PatternBaseline baseline = baselineMap.get(patternId);
        if (baseline == null) return 0.0;
        return baseline.getSigmaScore(currentCount, config.getSigmaThreshold());
    }

    public boolean isFrequencyAnomaly(String patternId, long currentCount) {
        double sigma = getSigmaScore(patternId, currentCount);
        return sigma > config.getSigmaThreshold();
    }

    public double[] getStats(String patternId) {
        return statsCache.get(patternId, id -> {
            PatternBaseline baseline = baselineMap.get(id);
            if (baseline == null || !baseline.statsValid) {
                return new double[]{0, 0, 0};
            }
            return new double[]{baseline.mean, baseline.stdDev, baseline.currentWindowCount.get()};
        });
    }

    public Map<String, Object> getBaselineInfo(String patternId) {
        PatternBaseline baseline = baselineMap.get(patternId);
        if (baseline == null) return Collections.emptyMap();

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("patternId", patternId);
        info.put("mean", baseline.mean);
        info.put("stdDev", baseline.stdDev);
        info.put("currentCount", baseline.currentWindowCount.get());
        info.put("statsValid", baseline.statsValid);
        info.put("historySlots", baseline.historySlots);
        info.put("windowMinutes", baseline.windowMinutes);

        List<Long> recentHistory = new ArrayList<>();
        for (int i = 0; i < Math.min(24, baseline.historySlots); i++) {
            int idx = (baseline.currentSlot - 1 - i + baseline.historySlots) % baseline.historySlots;
            recentHistory.add(baseline.historyCounts.get(idx));
        }
        info.put("recentHistory", recentHistory);

        return info;
    }

    public int getTrackedPatternCount() {
        return baselineMap.size();
    }

    public List<Map.Entry<String, Double>> getTopAnomalies(int limit) {
        List<Map.Entry<String, Double>> anomalies = new ArrayList<>();

        for (Map.Entry<String, PatternBaseline> entry : baselineMap.entrySet()) {
            PatternBaseline baseline = entry.getValue();
            double sigma = baseline.getSigmaScore(baseline.currentWindowCount.get(), config.getSigmaThreshold());
            if (sigma > 0) {
                anomalies.add(new AbstractMap.SimpleEntry<>(entry.getKey(), sigma));
            }
        }

        anomalies.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return anomalies.subList(0, Math.min(limit, anomalies.size()));
    }

    public void recordPattern(String patternId, String service) {
        PatternBaseline baseline = baselineMap.computeIfAbsent(
                patternId,
                id -> new PatternBaseline(
                        id,
                        config.getBaselineWindowMinutes() > 0 ? config.getBaselineWindowMinutes() : config.getFrequencyWindowMinutes(),
                        config.getBaselineHistoryDays()
                )
        );
        baseline.increment();
    }

    public boolean isColdStart(String patternId, String service) {
        PatternBaseline baseline = baselineMap.get(patternId);
        if (baseline == null) {
            return true;
        }
        return !baseline.statsValid;
    }

    public boolean hasEnoughData(String patternId, String service) {
        PatternBaseline baseline = baselineMap.get(patternId);
        if (baseline == null) {
            return false;
        }
        return baseline.statsValid;
    }

    public long getPatternCount(String patternId, String service, Duration duration) {
        PatternBaseline baseline = baselineMap.get(patternId);
        if (baseline == null) {
            return 0;
        }
        return baseline.currentWindowCount.get();
    }

    public double getMeanFrequency(String patternId, String service) {
        PatternBaseline baseline = baselineMap.get(patternId);
        if (baseline == null || !baseline.statsValid) {
            return 0;
        }
        return baseline.mean;
    }

    public double getStdDevFrequency(String patternId, String service) {
        PatternBaseline baseline = baselineMap.get(patternId);
        if (baseline == null || !baseline.statsValid) {
            return 0;
        }
        return baseline.stdDev;
    }
}
