package com.tsdbproxy.query.stream.config;

import com.tsdbproxy.query.stream.impl.monitor.PrometheusQueryMonitor;
import com.tsdbproxy.query.stream.spi.QueryMonitor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueryMonitorConfig {

    @Bean
    public QueryMonitor queryMonitor(MeterRegistry meterRegistry) {
        return new PrometheusQueryMonitor(meterRegistry);
    }
}
