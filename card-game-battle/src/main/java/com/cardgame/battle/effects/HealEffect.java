package com.cardgame.battle.effects;

import com.cardgame.battle.entity.BattleAction;
import com.cardgame.battle.entity.BattleContext;
import com.cardgame.common.entity.Effect;
import com.cardgame.common.entity.GameCharacter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HealEffect implements IEffect {

    @Override
    public void apply(BattleContext context, GameCharacter source, GameCharacter target,
                      Effect effectConfig, BattleAction action) {
        GameCharacter healTarget = target != null ? target : source;
        int healAmount = Math.min(effectConfig.getValue(), healTarget.getMaxHp() - healTarget.getCurrentHp());
        healTarget.heal(healAmount);
        action.setHealAmount(action.getHealAmount() + healAmount);
        action.setHealDone(action.getHealDone() + healAmount);
        action.setDescription(source.getName() + " heals " + healTarget.getName() + " for " + healAmount + " HP");
        log.debug("{} healed {} for {} HP", source.getName(), healTarget.getName(), healAmount);
    }

    @Override
    public boolean requiresTarget() {
        return false;
    }
}
