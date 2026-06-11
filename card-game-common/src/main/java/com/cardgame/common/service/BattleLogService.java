package com.cardgame.common.service;

import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;

public interface BattleLogService {
    void startBattleLogging(BattleContext context);

    void logAction(String battleId, BattleAction action);

    void endBattleLogging(String battleId, BattleContext context);
}
