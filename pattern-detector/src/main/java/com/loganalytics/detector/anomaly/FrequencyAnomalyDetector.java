package com.loganalytics.detector.anomaly;

import com.loganalytics.common.model.AnomalyEvent;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogPattern;
import com.loganalytics.common.util.IdUtils;
import com.loganalytics.detector.baseline.BaselineManager;
import com.loganalytics.detector.config.DetectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FrequencyAnomalyDetector {
    private static final Logger log = LoggerFactory.getLogger(FrequencyAnomalyDetector.class);

    private final DetectorConfig config;
    private final BaselineManager baselineManager;
    private final Map<String, Long> lastAlertTime;

    public FrequencyAnomalyDetector(DetectorConfig config, BaselineManager baselineManager) {
        this.config = config;
        this.baselineManager = baselineManager;
        this.lastAlertTime = new ConcurrentHashMap<>();
    }

    public List<AnomalyEvent> detect(List<LogPattern> patterns) {
        List<AnomalyEvent> anomalies = new ArrayList<>();
        long cooldownMs = config.getAnomalyCooldownMinutes() * 60_000L;
        long now = System.currentTimeMillis();

        for (LogPattern pattern : patterns) {
            baselineManager.processPattern(pattern);

            String patternId = pattern.getId();
            double[] stats = baselineManager.getStats(patternId);
            double mean = stats[0];
            double stdDev = stats[1];
            long currentCount = (long) stats[2];

            if (currentCount == 0) continue;

            double sigma = baselineManager.getSigmaScore(patternId, currentCount);

            if (sigma > config.getSigmaThreshold()) {
                Long lastAlert = lastAlertTime.get(patternId);
                if (lastAlert != null && (now - lastAlert) < cooldownMs) {
                    log.debug("Skipping frequency anomaly for {} due to cooldown", patternId);
                    continue;
                }

                AnomalyEvent anomaly = createAnomaly(pattern, currentCount, mean, stdDev, sigma);
                anomalies.add(anomaly);
                lastAlertTime.put(patternId, now);

                log.warn("Frequency anomaly detected: pattern={}, count={}, mean={:.2f}, stdDev={:.2f}, sigma={:.2f}",
                        pattern.getTemplate(), currentCount, mean, stdDev, sigma);
            }
        }

        return anomalies;
    }

    private AnomalyEvent createAnomaly(LogPattern pattern, long currentCount,
                                       double mean, double stdDev, double sigma) {
        AnomalyEvent anomaly = new AnomalyEvent(
                IdUtils.newAnomalyId(),
                AnomalyEvent.AnomalyType.FREQUENCY,
                Instant.now()
        );

        anomaly.setPatternId(pattern.getId());
        anomaly.setPatternTemplate(pattern.getTemplate());
        anomaly.setServiceName(pattern.getSampleService());
        anomaly.setLevel(pattern.getSampleLevel());
        anomaly.setSigmaScore(sigma);

        if (sigma >= 5) {
            anomaly.setSeverity(AnomalyEvent.Severity.CRITICAL);
        } else if (sigma >= 4) {
            anomaly.setSeverity(AnomalyEvent.Severity.HIGH);
        } else if (sigma >= 3.5) {
            anomaly.setSeverity(AnomalyEvent.Severity.MEDIUM);
        } else {
            anomaly.setSeverity(AnomalyEvent.Severity.LOW);
        }

        anomaly.addDetail("currentCount", currentCount);
        anomaly.addDetail("baselineMean", mean);
        anomaly.addDetail("baselineStdDev", stdDev);
        anomaly.addDetail("threshold", config.getSigmaThreshold());
        anomaly.addDetail("deviationPercent", mean > 0 ? ((currentCount - mean) / mean * 100) : 100);
        anomaly.addDetail("windowMinutes", config.getFrequencyWindowMinutes());
        anomaly.addDetail("patternTotalCount", pattern.getTotalCount());
        anomaly.addDetail("description", String.format(
                "Pattern frequency spiked to %d (baseline: %.2f ± %.2f, %.2fσ deviation)",
                currentCount, mean, stdDev, sigma
        ));

        return anomaly;
    }

    public List<Map.Entry<String, Double>> getTopAnomalies(int limit) {
        return baselineManager.getTopAnomalies(limit);
    }

    public Map<String, Object> getDiagnostics() {
        return Map.of(
                "trackedPatterns", baselineManager.getTrackedPatternCount(),
                "sigmaThreshold", config.getSigmaThreshold(),
                "windowMinutes", config.getFrequencyWindowMinutes(),
                "cooldownMinutes", config.getAnomalyCooldownMinutes()
        );
    }
}
