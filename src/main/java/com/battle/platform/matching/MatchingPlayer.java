package com.battle.platform.matching;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchingPlayer {
    private Long playerId;
    private Integer serverId;
    private Long combatPower;
    private Double rating;
    private Double serverPowerScore;
    private Long joinTimeMs;
    private Long guildId;

    public double getCompositeRating() {
        double combatWeight = 0.4;
        double serverWeight = 0.3;
        double personalWeight = 0.3;

        double normalizedCombat = Math.log10(Math.max(combatPower, 1)) * 1000;
        double normalizedServer = serverPowerScore != null ? serverPowerScore : 0;
        double personalRating = rating != null ? rating : 1000;

        return normalizedCombat * combatWeight + normalizedServer * serverWeight + personalRating * personalWeight;
    }

    public int getRatingBracket(int bracketSize) {
        return (int) (getCompositeRating() / bracketSize);
    }

    public long getWaitTimeMs() {
        return System.currentTimeMillis() - joinTimeMs;
    }

    public double getWaitTimePriority(double weight) {
        long waitSeconds = getWaitTimeMs() / 1000;
        return waitSeconds * weight;
    }
}
