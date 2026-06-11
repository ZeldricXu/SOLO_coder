package com.cardgame.battle.effects;

import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.Effect;
import com.cardgame.common.entity.GameCharacter;

public interface IEffect {
    void apply(BattleContext context, GameCharacter source, GameCharacter target,
               Effect effectConfig, BattleAction action);

    boolean requiresTarget();
}
