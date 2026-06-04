package com.battle.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "battle.battlefield")
public class BattlefieldProperties {
    private int maxPlayers = 200;
    private int moveSyncFps = 20;
    private int aoiGridSize = 100;
    private long idleTimeoutMs = 300000;
}
