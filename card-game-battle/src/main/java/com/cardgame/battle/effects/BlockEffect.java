package com.cardgame.battle.effects;

import com.cardgame.battle.entity.BattleAction;
import com.cardgame.battle.entity.BattleContext;
import com.cardgame.common.entity.Effect;
import com.cardgame.common.entity.GameCharacter;
import com.cardgame.common.enums.BuffType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BlockEffect implements IEffect {

    @Override
    public void apply(BattleContext context, GameCharacter source, GameCharacter target,
                      Effect effectConfig, BattleAction action) {
        int block = calculateBlock(source, effectConfig.getValue());
        source.addBlock(block);
        action.setBlockGained(action.getBlockGained() + block);
        action.setDescription(source.getName() + " gains " + block + " block");
        log.debug("{} gained {} block", source.getName(), block);
    }

    @Override
    public boolean requiresTarget() {
        return false;
    }

    private int calculateBlock(GameCharacter character, int baseBlock) {
        int block = baseBlock;

        int dexterity = character.getBuffStacks(BuffType.DEXTERITY.name());
        block += dexterity;

        if (character.hasBuff(BuffType.FRAIL.name())) {
            block = (int) (block * 0.75);
        }

        return Math.max(0, block);
    }
}
