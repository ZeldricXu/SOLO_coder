package com.battle.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "battle.score")
public class ScoreProperties {
    private int killBase = 100;
    private int assistBase = 50;
    private int captureBase = 200;
    private int streakBonusThreshold = 3;
    private double streakMultiplier = 0.5;
}
