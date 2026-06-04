package com.battle.platform.battlefield.event;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PlayerDeathEvent {
    private String battleId;
    private Long killerId;
    private Long victimId;
    private int skillId;
}
