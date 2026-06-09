package com.cardgame.rank.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RankEntry {
    private String playerId;
    private String playerName;
    private int score;
    private int rank;
    private int highestFloor;
    private int totalWins;
    private double winRate;
    private long timestamp;
    private String seasonId;

    public void updateScore(int newScore) {
        if (newScore > this.score) {
            this.score = newScore;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public void updateHighestFloor(int floor) {
        if (floor > this.highestFloor) {
            this.highestFloor = floor;
        }
    }
}
