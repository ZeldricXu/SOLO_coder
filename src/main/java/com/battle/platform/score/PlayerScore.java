package com.battle.platform.score;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerScore {
    private Long playerId;
    private Long guildId;
    private int totalScore;
    private int kills;
    private int deaths;
    private int assists;
    private int captures;
    private int currentStreak;
    private int maxStreak;
    private int headshots;
    private double damageDealt;
    private double damageTaken;
}
