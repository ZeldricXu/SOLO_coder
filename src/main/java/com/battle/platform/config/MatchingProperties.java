package com.battle.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "battle.matching")
public class MatchingProperties {
    private long tickIntervalMs = 500;
    private long maxWaitTimeMs = 120000;
    private int ratingBracketSize = 200;
    private double waitTimeWeight = 0.5;
}
