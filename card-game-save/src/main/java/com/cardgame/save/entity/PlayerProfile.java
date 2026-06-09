package com.cardgame.save.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerProfile {
    private String playerId;
    private String username;
    private String nickname;
    private int level;
    private int experience;
    private int totalPlayTimeSeconds;
    private int totalGamesPlayed;
    private int totalWins;
    private int highestFloorReached;
    private int totalGoldEarned;
    @Builder.Default
    private List<String> unlockedCardIds = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> achievements = new HashMap<>();
    @Builder.Default
    private Map<String, Integer> stats = new HashMap<>();
    private long createdAt;
    private long lastLoginAt;
    private boolean online;
    private String currentSaveId;
    private String currentRoomId;

    public void addExperience(int exp) {
        this.experience += exp;
        while (this.experience >= getExpForNextLevel()) {
            this.experience -= getExpForNextLevel();
            this.level++;
        }
    }

    private int getExpForNextLevel() {
        return (int) (100 * Math.pow(1.5, this.level - 1));
    }

    public void incrementGamesPlayed(boolean won) {
        this.totalGamesPlayed++;
        if (won) {
            this.totalWins++;
        }
    }

    public void updateHighestFloor(int floor) {
        if (floor > this.highestFloorReached) {
            this.highestFloorReached = floor;
        }
    }

    public double getWinRate() {
        if (totalGamesPlayed == 0) return 0.0;
        return (double) totalWins / totalGamesPlayed;
    }

    public void unlockCard(String cardId) {
        if (!unlockedCardIds.contains(cardId)) {
            unlockedCardIds.add(cardId);
        }
    }

    public boolean hasUnlockedCard(String cardId) {
        return unlockedCardIds.contains(cardId);
    }

    public void addStat(String statName, int value) {
        stats.put(statName, stats.getOrDefault(statName, 0) + value);
    }
}
