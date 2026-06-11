package com.cardgame.common.entity;

import com.cardgame.common.enums.BuffType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Buff {
    private BuffType type;
    private int stacks;
    private int duration;
    private String sourceId;
    private boolean isDebuff;
    private String instanceId;

    public boolean isExpired() {
        return duration <= 0 && stacks <= 0;
    }

    public void decreaseDuration() {
        if (duration > 0) {
            duration--;
        }
    }

    public void addStacks(int amount) {
        this.stacks += amount;
    }

    public void removeStacks(int amount) {
        this.stacks = Math.max(0, this.stacks - amount);
    }
}
