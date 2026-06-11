package com.cardgame.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BattleAction {
    private String actionId;
    private int turn;
    private int round;
    private String actorId;
    private boolean isPlayerAction;
    private String actionType;
    private Card cardUsed;
    private String targetId;
    @Builder.Default
    private List<String> targetIds = new ArrayList<>();
    private int damageDealt;
    private int blockGained;
    private int healAmount;
    private int healDone;
    private Map<String, Object> buffsApplied;
    private Map<String, Object> buffsRemoved;
    private int cardsDrawn;
    private int energySpent;
    private int energyGained;
    private long timestamp;
    private String description;
    private Map<String, Object> extraData;
}
