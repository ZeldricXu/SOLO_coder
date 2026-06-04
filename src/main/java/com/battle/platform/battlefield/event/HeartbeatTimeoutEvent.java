package com.battle.platform.battlefield.event;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HeartbeatTimeoutEvent {
    private String battleId;
    private Long playerId;
}
