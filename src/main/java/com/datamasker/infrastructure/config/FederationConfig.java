package com.datamasker.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "datamasker.federation")
public class FederationConfig {

    private int minParticipants = 2;

    private int maxRounds = 100;

    private double convergenceThreshold = 0.001;
}
