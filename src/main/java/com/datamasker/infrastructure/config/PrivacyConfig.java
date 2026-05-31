package com.datamasker.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "datamasker.privacy")
public class PrivacyConfig {

    private double defaultEpsilon = 1.0;

    private double defaultDelta = 1.0E-5;

    private double maxBudget = 10.0;
}
