package com.cardgame.battle.effects;

import com.cardgame.battle.entity.BattleAction;
import com.cardgame.battle.entity.BattleContext;
import com.cardgame.common.entity.Effect;
import com.cardgame.common.entity.GameCharacter;
import com.cardgame.common.entity.Player;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnergyEffect implements IEffect {

    @Override
    public void apply(BattleContext context, GameCharacter source, GameCharacter target,
                      Effect effectConfig, BattleAction action) {
        if (source instanceof Player player) {
            player.setCurrentEnergy(player.getCurrentEnergy() + effectConfig.getValue());
            action.setEnergyGained(action.getEnergyGained() + effectConfig.getValue());
            action.setDescription(source.getName() + " gains " + effectConfig.getValue() + " energy");
            log.debug("{} gained {} energy", source.getName(), effectConfig.getValue());
        }
    }

    @Override
    public boolean requiresTarget() {
        return false;
    }
}
