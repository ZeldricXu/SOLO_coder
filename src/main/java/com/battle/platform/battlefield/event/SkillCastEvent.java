package com.battle.platform.battlefield.event;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SkillCastEvent {
    private String battleId;
    private Long playerId;
    private int skillId;
    private double targetX;
    private double targetZ;
}
