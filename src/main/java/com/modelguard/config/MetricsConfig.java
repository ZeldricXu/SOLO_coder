package com.modelguard.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
public class MetricsConfig {

    @Autowired
    private MeterRegistry meterRegistry;

    public static Counter requestCounter;
    public static Counter errorCounter;
    public static Timer requestTimer;
    public static AtomicLong activeRequests;
    public static Counter gpuTaskSubmittedCounter;
    public static Counter gpuTaskCompletedCounter;
    public static Counter inferenceRequestCounter;
    public static Counter inferenceFallbackCounter;

    @PostConstruct
    public void initMetrics() {
        requestCounter = Counter.builder("modelguard.requests.total")
                .description("Total number of requests")
                .tag("application", "modelguard")
                .register(meterRegistry);

        errorCounter = Counter.builder("modelguard.requests.errors")
                .description("Number of error requests")
                .tag("application", "modelguard")
                .register(meterRegistry);

        requestTimer = Timer.builder("modelguard.request.latency")
                .description("Request latency")
                .tag("application", "modelguard")
                .register(meterRegistry);

        activeRequests = new AtomicLong(0);
        meterRegistry.gauge("modelguard.requests.active", activeRequests);

        gpuTaskSubmittedCounter = Counter.builder("modelguard.gpu.tasks.submitted")
                .description("Number of submitted GPU tasks")
                .register(meterRegistry);

        gpuTaskCompletedCounter = Counter.builder("modelguard.gpu.tasks.completed")
                .description("Number of completed GPU tasks")
                .register(meterRegistry);

        inferenceRequestCounter = Counter.builder("modelguard.inference.requests")
                .description("Number of inference requests")
                .register(meterRegistry);

        inferenceFallbackCounter = Counter.builder("modelguard.inference.fallbacks")
                .description("Number of inference fallbacks")
                .register(meterRegistry);
    }
}
