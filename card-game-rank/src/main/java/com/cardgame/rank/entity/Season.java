package com.cardgame.rank.entity;

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
public class Season {
    private String seasonId;
    private String name;
    private String description;
    private long startTime;
    private long endTime;
    private boolean active;
    @Builder.Default
    private Map<String, Object> rewards = new HashMap<>();
    @Builder.Default
    private List<String> rewardTiers = new ArrayList<>();
    private String version;

    public boolean isInProgress() {
        long now = System.currentTimeMillis();
        return active && now >= startTime && now <= endTime;
    }

    public boolean hasEnded() {
        return System.currentTimeMillis() > endTime;
    }

    public long getDaysRemaining() {
        long remaining = endTime - System.currentTimeMillis();
        return Math.max(0, remaining / (1000 * 60 * 60 * 24));
    }

    public int getRewardTier(int rank) {
        if (rank <= 1) return 0;
        if (rank <= 10) return 1;
        if (rank <= 100) return 2;
        if (rank <= 1000) return 3;
        return 4;
    }

    public String getRewardForRank(int rank) {
        int tier = getRewardTier(rank);
        if (tier < rewardTiers.size()) {
            return rewardTiers.get(tier);
        }
        return "participation";
    }
}
