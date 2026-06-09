package com.cardgame.room.entity;

import com.cardgame.common.enums.PlayerClass;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchRequest {
    private String playerId;
    private String playerName;
    private PlayerClass playerClass;
    private int playerLevel;
    private long requestTime;
    private int targetPlayers;

    public long getWaitTime() {
        return System.currentTimeMillis() - requestTime;
    }
}
