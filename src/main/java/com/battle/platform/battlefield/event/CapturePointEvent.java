package com.battle.platform.battlefield.event;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CapturePointEvent {
    private String battleId;
    private Long playerId;
    private int pointId;
}
