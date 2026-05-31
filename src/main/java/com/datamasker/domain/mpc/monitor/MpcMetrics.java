package com.datamasker.domain.mpc.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class MpcMetrics {

    private final MeterRegistry meterRegistry;

    private final AtomicInteger activeSessionsGauge = new AtomicInteger(0);
    private final AtomicInteger pendingInputsGauge = new AtomicInteger(0);

    private Timer sessionCreationTimer;
    private Timer inputSubmissionTimer;
    private Timer computationTimer;
    private Counter sessionsCreated;
    private Counter sessionsCompleted;
    private Counter sessionsFailed;
    private Counter sessionsTimedOut;
    private DistributionSummary partyCountDistribution;

    @PostConstruct
    public void init() {
        sessionCreationTimer = Timer.builder("mpc.session.creation")
                .description("MPC session creation latency")
                .register(meterRegistry);

        inputSubmissionTimer = Timer.builder("mpc.input.submission")
                .description("Input submission latency")
                .register(meterRegistry);

        computationTimer = Timer.builder("mpc.computation")
                .description("Computation execution latency")
                .register(meterRegistry);

        sessionsCreated = Counter.builder("mpc.sessions.created")
                .description("Total MPC sessions created")
                .register(meterRegistry);

        sessionsCompleted = Counter.builder("mpc.sessions.completed")
                .description("Total MPC sessions completed")
                .register(meterRegistry);

        sessionsFailed = Counter.builder("mpc.sessions.failed")
                .description("Total MPC sessions failed")
                .register(meterRegistry);

        sessionsTimedOut = Counter.builder("mpc.sessions.timedout")
                .description("Total MPC sessions timed out")
                .register(meterRegistry);

        partyCountDistribution = DistributionSummary.builder("mpc.party.count")
                .description("Party count per session distribution")
                .register(meterRegistry);

        Gauge.builder("mpc.sessions.active", activeSessionsGauge, AtomicInteger::get)
                .description("Current active MPC sessions")
                .register(meterRegistry);

        Gauge.builder("mpc.inputs.pending", pendingInputsGauge, AtomicInteger::get)
                .description("Pending input count")
                .register(meterRegistry);
    }

    public void recordSessionCreation(long durationMs, String protocol, int partyCount) {
        sessionCreationTimer.record(java.time.Duration.ofMillis(durationMs));
        sessionsCreated.increment();
        partyCountDistribution.record(partyCount);
    }

    public void recordInputSubmission(long durationMs, String sessionId) {
        inputSubmissionTimer.record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordComputation(long durationMs, String protocol, boolean success) {
        computationTimer.record(java.time.Duration.ofMillis(durationMs));
        if (success) {
            sessionsCompleted.increment();
        } else {
            sessionsFailed.increment();
        }
    }

    public void recordTimeout() {
        sessionsTimedOut.increment();
    }

    public void incrementActive() {
        activeSessionsGauge.incrementAndGet();
    }

    public void decrementActive() {
        activeSessionsGauge.decrementAndGet();
    }

    public void updatePendingInputs(int count) {
        pendingInputsGauge.set(count);
    }

    public int getActiveSessions() {
        return activeSessionsGauge.get();
    }

    public double getAvgCreationLatency() {
        return sessionCreationTimer.mean(TimeUnit.MILLISECONDS);
    }

    public double getAvgComputationLatency() {
        return computationTimer.mean(TimeUnit.MILLISECONDS);
    }

    public long getTotalCreated() {
        return (long) sessionsCreated.count();
    }

    public long getTotalCompleted() {
        return (long) sessionsCompleted.count();
    }

    public long getTotalFailed() {
        return (long) sessionsFailed.count();
    }

    public long getTotalTimedOut() {
        return (long) sessionsTimedOut.count();
    }
}
