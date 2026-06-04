package com.battle.platform.battlefield.event;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ScoreAddEvent {
    private String battleId;
    private Long playerId;
    private int scoreDelta;
    private String reason;
}
