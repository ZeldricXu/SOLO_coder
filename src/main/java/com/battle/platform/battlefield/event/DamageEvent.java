package com.battle.platform.battlefield.event;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DamageEvent {
    private String battleId;
    private Long attackerId;
    private Long victimId;
    private double damage;
    private int skillId;
    private boolean headshot;
}
