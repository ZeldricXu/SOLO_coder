package com.cardgame.replay.config;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Data
@Component
public class BattleLogSamplingConfig {

    private boolean enabled = true;
    private int bossFloorInterval = 10;
    private int logRetentionDays = 7;
    private int cleanupCron = 0;

    private Set<String> criticalActionTypes = new HashSet<>(Arrays.asList(
            "DRAW_CARD",
            "PLAY_CARD",
            "DEAL_DAMAGE",
            "BATTLE_START",
            "BATTLE_END",
            "PLAYER_DEFEAT",
            "ENEMY_DEFEAT"
    ));

    private Set<String> detailedActionTypes = new HashSet<>(Arrays.asList(
            "DRAW_CARD",
            "PLAY_CARD",
            "DEAL_DAMAGE",
            "GAIN_BLOCK",
            "HEAL",
            "APPLY_BUFF",
            "REMOVE_BUFF",
            "END_TURN",
            "START_TURN",
            "USE_POTION",
            "CARD_REWARD",
            "BATTLE_START",
            "BATTLE_END",
            "PLAYER_DEFEAT",
            "ENEMY_DEFEAT"
    ));

    public boolean isBossBattle(int floor) {
        return floor > 0 && floor % bossFloorInterval == 0;
    }

    public boolean shouldLogAction(String actionType, boolean isBossBattle) {
        if (!enabled || isBossBattle) {
            return true;
        }
        return criticalActionTypes.contains(actionType);
    }

    public Set<String> getActionTypesForBattle(boolean isBossBattle) {
        if (!enabled || isBossBattle) {
            return detailedActionTypes;
        }
        return criticalActionTypes;
    }
}
