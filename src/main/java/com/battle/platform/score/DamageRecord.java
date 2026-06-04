package com.battle.platform.score;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DamageRecord {
    private Long attackerId;
    private Long victimId;
    private double damage;
    private int skillId;
    private boolean isHeadshot;
    private long timestamp;
}
