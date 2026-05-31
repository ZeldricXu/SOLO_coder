package com.monitoring.trace.config;

import com.monitoring.trace.sampling.SamplingStrategy;
import com.monitoring.trace.service.TraceCollectorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;

import java.util.List;

@Configuration
@ComponentScan(basePackages = "com.monitoring.trace")
public class TraceAutoConfiguration {

    @Bean
    public TraceCollectorService traceCollectorService(List<SamplingStrategy> strategies) {
        TraceCollectorService service = new TraceCollectorService();
        strategies.forEach(service::registerStrategy);
        return service;
    }
}
