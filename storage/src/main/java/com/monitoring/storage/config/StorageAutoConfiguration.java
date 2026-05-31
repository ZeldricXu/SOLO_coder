package com.monitoring.storage.config;

import com.monitoring.storage.engine.StorageEngine;
import com.monitoring.storage.service.TimeSeriesService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;

import java.util.List;

@Configuration
@ComponentScan(basePackages = "com.monitoring.storage")
public class StorageAutoConfiguration {

    @Bean
    public TimeSeriesService timeSeriesService(List<StorageEngine> engines,
                                               com.monitoring.storage.preaggregator.PreAggregator preAggregator,
                                               com.monitoring.dal.repository.MetricDataRepository metricDataRepository) {
        TimeSeriesService service = new TimeSeriesService(preAggregator, metricDataRepository);
        engines.forEach(service::registerEngine);
        return service;
    }
}
