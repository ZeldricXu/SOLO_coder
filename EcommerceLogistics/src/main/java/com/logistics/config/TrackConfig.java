package com.logistics.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "logistics.track")
public class TrackConfig {

    private int highFrequencyThreshold = 3;
    private long highFrequencyWindowSeconds = 5;
    private int batchFlushThreshold = 10;
    private long batchFlushIntervalMs = 2000;
}
