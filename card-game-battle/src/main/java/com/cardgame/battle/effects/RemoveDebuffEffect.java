package com.cardgame.battle.effects;

import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.Buff;
import com.cardgame.common.entity.Effect;
import com.cardgame.common.entity.GameCharacter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class RemoveDebuffEffect implements IEffect {

    @Override
    public void apply(BattleContext context, GameCharacter source, GameCharacter target,
                      Effect effectConfig, BattleAction action) {
        GameCharacter removeTarget = target != null ? target : source;
        if (removeTarget == null) return;

        int removed = 0;
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Buff> entry : removeTarget.getBuffs().entrySet()) {
            if (entry.getValue().isDebuff()) {
                toRemove.add(entry.getKey());
                removed++;
            }
        }
        toRemove.forEach(removeTarget::removeBuff);
        action.getBuffsRemoved().put("debuffs", removed);
        action.setDescription(removeTarget.getName() + " removes " + removed + " debuffs");
        log.debug("{} removed {} debuffs", removeTarget.getName(), removed);
    }

    @Override
    public boolean requiresTarget() {
        return false;
    }
}
