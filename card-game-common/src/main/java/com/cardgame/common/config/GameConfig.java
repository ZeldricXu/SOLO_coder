package com.cardgame.common.config;

import lombok.Data;

@Data
public class GameConfig {
    private int maxPlayersPerRoom = 4;
    private int maxHandSize = 10;
    private int defaultDrawPerTurn = 5;
    private int defaultEnergyPerTurn = 3;
    private int defaultMaxEnergy = 3;
    private int basePlayerHp = 80;
    private int basePlayerSpeed = 10;
    private int mapFloors = 15;
    private int nodesPerFloor = 4;
    private int eliteEveryXFloors = 3;
    private int bossFloor = 15;
    private int reconnectTimeoutSeconds = 120;
    private int heartbeatIntervalSeconds = 30;
    private int maxMatchQueueSize = 1000;
    private int matchTimeoutSeconds = 60;
}
