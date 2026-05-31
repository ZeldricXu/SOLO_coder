package com.monitoring.anomaly.config;

import com.monitoring.anomaly.algorithm.AnomalyDetector;
import com.monitoring.anomaly.service.AnomalyDetectionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;

import java.util.List;

@Configuration
@ComponentScan(basePackages = "com.monitoring.anomaly")
public class AnomalyAutoConfiguration {

    @Bean
    public AnomalyDetectionService anomalyDetectionService(List<AnomalyDetector> detectors) {
        AnomalyDetectionService service = new AnomalyDetectionService();
        detectors.forEach(service::registerDetector);
        return service;
    }
}
