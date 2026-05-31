package com.datamasker.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "datamasker.classification")
public class ClassificationConfig {

    private int scanBatchSize = 1000;

    private double sensitiveThreshold = 0.85;
}
