package com.battle.platform.battlefield.event;

import com.battle.platform.battlefield.PlayerPosition;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PlayerMoveEvent {
    private String battleId;
    private Long playerId;
    private PlayerPosition position;
}
