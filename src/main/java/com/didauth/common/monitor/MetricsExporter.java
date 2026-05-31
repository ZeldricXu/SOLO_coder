package com.didauth.common.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@Getter
@RequiredArgsConstructor
public class MetricsExporter {

    private final MeterRegistry meterRegistry;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Metrics Exporter initialized with Prometheus integration");

        registerCounter("requests.total", "Total number of requests");
        registerCounter("requests.success", "Total successful requests");
        registerCounter("requests.failed", "Total failed requests");

        registerTimer("request.duration", "Request duration in milliseconds");
    }

    public Counter registerCounter(String name, String description) {
        return counters.computeIfAbsent(name, k ->
                Counter.builder(name)
                        .description(description)
                        .register(meterRegistry));
    }

    public Timer registerTimer(String name, String description) {
        return timers.computeIfAbsent(name, k ->
                Timer.builder(name)
                        .description(description)
                        .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                        .register(meterRegistry));
    }

    public void registerGauge(String name, String description) {
        AtomicLong value = new AtomicLong(0);
        Gauge.builder(name, value, AtomicLong::get)
                .description(description)
                .register(meterRegistry);
        gauges.put(name, value);
    }

    public void incrementCounter(String name) {
        Counter counter = counters.get(name);
        if (counter != null) {
            counter.increment();
        }
    }

    public void incrementCounter(String name, double amount) {
        Counter counter = counters.get(name);
        if (counter != null) {
            counter.increment(amount);
        }
    }

    public void setGauge(String name, long value) {
        AtomicLong gauge = gauges.get(name);
        if (gauge != null) {
            gauge.set(value);
        }
    }

    public Timer.Sample startTimer(String name) {
        return Timer.start(meterRegistry);
    }

    public long stopTimer(Timer.Sample sample, String name) {
        Timer timer = timers.get(name);
        if (timer != null) {
            return sample.stop(timer);
        }
        return 0;
    }
}
