package com.observability.slo.service;

import com.observability.common.util.IdGenerator;
import com.observability.slo.entity.SloConfigEntity;
import com.observability.slo.model.SloStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SloService {

    private final Map<String, SloConfigEntity> sloConfigs = new ConcurrentHashMap<>();
    private final Map<String, SloTracker> sloTrackers = new ConcurrentHashMap<>();

    public Mono<SloConfigEntity> createSlo(String name, String sliMetric, double target,
                                            int timeWindow, double burnRateThreshold,
                                            Map<String, Object> notificationConfig) {
        return Mono.fromCallable(() -> {
            String sloId = IdGenerator.generateId("slo");
            double errorBudget = 1.0 - target;

            SloConfigEntity config = new SloConfigEntity();
            config.setSloId(sloId);
            config.setName(name);
            config.setSliMetric(sliMetric);
            config.setTarget(target);
            config.setTimeWindow(timeWindow);
            config.setErrorBudget(errorBudget);
            config.setBurnRateThreshold(burnRateThreshold);
            config.setNotificationConfig(notificationConfig);
            config.setEnabled(true);

            sloConfigs.put(sloId, config);
            sloTrackers.put(sloId, new SloTracker(timeWindow, errorBudget));

            log.info("SLO created - sloId: {}, name: {}, target: {}", sloId, name, target);
            return config;
        });
    }

    public Mono<SloStatus> getSloStatus(String sloId) {
        return Mono.fromCallable(() -> {
            SloConfigEntity config = sloConfigs.get(sloId);
            if (config == null) {
                throw new RuntimeException("SLO not found: " + sloId);
            }

            SloTracker tracker = sloTrackers.get(sloId);
            if (tracker == null) {
                tracker = new SloTracker(config.getTimeWindow(), config.getErrorBudget());
                sloTrackers.put(sloId, tracker);
            }

            SloStatus status = new SloStatus();
            status.setSloId(sloId);
            status.setName(config.getName());
            status.setSliValue(tracker.calculateSLI());
            status.setTarget(config.getTarget());
            status.setErrorBudgetRemaining(tracker.getRemainingBudget());
            status.setErrorBudgetConsumed(tracker.getConsumedBudget());
            status.setBurnRate(tracker.calculateBurnRate());
            status.setStatus(determineStatus(status.getBurnRate(), config.getBurnRateThreshold()));
            status.setTimeWindowElapsed(tracker.getElapsedTime());
            status.setTimeWindowRemaining(config.getTimeWindow() - tracker.getElapsedTime());

            return status;
        });
    }

    public Mono<List<SloStatus>> getAllSloStatus() {
        return Mono.fromCallable(() -> {
            List<SloStatus> statuses = new ArrayList<>();
            for (String sloId : sloConfigs.keySet()) {
                try {
                    statuses.add(getSloStatus(sloId).block());
                } catch (Exception e) {
                    log.error("Failed to get status for SLO: {}", sloId, e);
                }
            }
            return statuses;
        });
    }

    public Mono<Void> recordSliValue(String sloId, double sliValue, boolean isGood) {
        return Mono.fromRunnable(() -> {
            SloTracker tracker = sloTrackers.computeIfAbsent(sloId,
                    k -> {
                        SloConfigEntity config = sloConfigs.get(k);
                        if (config == null) {
                            throw new RuntimeException("SLO not found: " + k);
                        }
                        return new SloTracker(config.getTimeWindow(), config.getErrorBudget());
                    });
            tracker.recordEvent(sliValue, isGood);
        });
    }

    public Mono<Void> deleteSlo(String sloId) {
        return Mono.fromRunnable(() -> {
            sloConfigs.remove(sloId);
            sloTrackers.remove(sloId);
            log.info("SLO deleted - sloId: {}", sloId);
        });
    }

    public Mono<List<SloConfigEntity>> listSlos() {
        return Mono.fromCallable(() -> new ArrayList<>(sloConfigs.values()));
    }

    private String determineStatus(double burnRate, double threshold) {
        if (burnRate >= threshold * 2) {
            return "critical";
        } else if (burnRate >= threshold) {
            return "warning";
        } else if (burnRate >= threshold * 0.5) {
            return "caution";
        } else {
            return "healthy";
        }
    }

    private static class SloTracker {
        private final long timeWindow;
        private final double totalBudget;
        private final LinkedList<SliEvent> events = new LinkedList<>();
        private int goodEvents;
        private int badEvents;
        private long startTime;

        SloTracker(int timeWindow, double totalBudget) {
            this.timeWindow = timeWindow;
            this.totalBudget = totalBudget;
            this.startTime = System.currentTimeMillis();
        }

        synchronized void recordEvent(double sliValue, boolean isGood) {
            long now = System.currentTimeMillis();
            events.add(new SliEvent(now, sliValue, isGood));
            if (isGood) {
                goodEvents++;
            } else {
                badEvents++;
            }

            long windowStart = now - timeWindow * 1000L;
            while (!events.isEmpty() && events.getFirst().timestamp < windowStart) {
                SliEvent removed = events.removeFirst();
                if (removed.isGood) {
                    goodEvents--;
                } else {
                    badEvents--;
                }
            }
        }

        double calculateSLI() {
            int total = goodEvents + badEvents;
            if (total == 0) {
                return 1.0;
            }
            return (double) goodEvents / total;
        }

        double getRemainingBudget() {
            double currentSli = calculateSLI();
            double currentError = 1.0 - currentSli;
            return Math.max(0, totalBudget - currentError);
        }

        double getConsumedBudget() {
            double currentSli = calculateSLI();
            return Math.max(0, 1.0 - currentSli);
        }

        double calculateBurnRate() {
            double elapsedFraction = (double) getElapsedTime() / timeWindow;
            if (elapsedFraction <= 0) {
                return 0;
            }
            double consumedBudget = getConsumedBudget();
            return consumedBudget / (totalBudget * elapsedFraction);
        }

        long getElapsedTime() {
            return (System.currentTimeMillis() - startTime) / 1000;
        }

        private record SliEvent(long timestamp, double value, boolean isGood) {}
    }
}
