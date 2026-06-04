package com.battle.platform.battlefield.event;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PlayerDisconnectedEvent {
    private String battleId;
    private Long playerId;
    private String reason;
}
