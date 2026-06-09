package com.cardgame.rank.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyChallenge {
    private String challengeId;
    private String date;
    private long seed;
    private String description;
    private String difficulty;
    private int targetFloor;
    private int scoreMultiplier;
    @Builder.Default
    private java.util.Map<String, Object> modifiers = new java.util.HashMap<>();
    private long startTime;
    private long endTime;

    public boolean isActive() {
        long now = System.currentTimeMillis();
        return now >= startTime && now <= endTime;
    }

    public int calculateFinalScore(int baseScore, int floorReached) {
        int score = baseScore * scoreMultiplier;
        if (floorReached >= targetFloor) {
            score += 1000;
        }
        return score;
    }
}
