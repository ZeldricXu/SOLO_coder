package com.battle.platform.anticheat;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PlayerBehaviorProfile {
    private Long playerId;
    private int totalKills;
    private int headshots;
    private int totalBattles;
    private String currentBattleId;
    private List<Double> recentSpeeds;
    private List<double[]> recentPositions;
    private List<Long> recentTimestamps;

    private static final int MAX_SPEED_HISTORY = 100;
    private static final int MAX_POSITION_HISTORY = 200;

    public PlayerBehaviorProfile(Long playerId) {
        this.playerId = playerId;
        this.recentSpeeds = new ArrayList<>();
        this.recentPositions = new ArrayList<>();
        this.recentTimestamps = new ArrayList<>();
    }

    public void recordKill(boolean isHeadshot) {
        totalKills++;
        if (isHeadshot) {
            headshots++;
        }
    }

    public void recordBattle(String battleId) {
        totalBattles++;
        this.currentBattleId = battleId;
    }

    public void recordMovement(double distance, double speed, long deltaTimeMs) {
        recentSpeeds.add(speed);
        if (recentSpeeds.size() > MAX_SPEED_HISTORY) {
            recentSpeeds.remove(0);
        }
    }

    public void recordPosition(double x, double z, long timestamp) {
        recentPositions.add(new double[]{x, z});
        recentTimestamps.add(timestamp);
        if (recentPositions.size() > MAX_POSITION_HISTORY) {
            recentPositions.remove(0);
            recentTimestamps.remove(0);
        }
    }

    public double getHeadshotRate() {
        if (totalKills == 0) return 0;
        return (double) headshots / totalKills;
    }

    public double getAverageSpeed() {
        if (recentSpeeds.isEmpty()) return 0;
        return recentSpeeds.stream().mapToDouble(d -> d).average().orElse(0);
    }

    public double getMaxSpeed() {
        if (recentSpeeds.isEmpty()) return 0;
        return recentSpeeds.stream().mapToDouble(d -> d).max().orElse(0);
    }
}
