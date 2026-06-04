package com.battle.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "battle.anticheat")
public class AntiCheatProperties {
    private double headshotRateThreshold = 0.7;
    private double speedHackThreshold = 15.0;
    private int sameIpPlayerLimit = 3;
}
