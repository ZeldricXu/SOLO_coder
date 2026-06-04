package com.battle.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoreUpdateNotification {
    private Long playerId;
    private int totalScore;
    private int kills;
    private int assists;
    private int captures;
    private int currentStreak;
}
