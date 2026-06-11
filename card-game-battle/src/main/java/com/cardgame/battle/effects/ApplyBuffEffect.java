package com.cardgame.battle.effects;

import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.BuffTriggerContext;
import com.cardgame.battle.engine.BuffSystem;
import com.cardgame.common.entity.Buff;
import com.cardgame.common.entity.Effect;
import com.cardgame.common.entity.GameCharacter;
import com.cardgame.common.enums.BuffType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class ApplyBuffEffect implements IEffect {

    private final BuffSystem buffSystem;
    private final boolean isDebuff;

    @Override
    public void apply(BattleContext context, GameCharacter source, GameCharacter target,
                      Effect effectConfig, BattleAction action) {
        if (target == null) {
            target = source;
        }

        Buff buff = effectConfig.getBuff();
        if (buff == null) {
            log.warn("No buff configured for effect");
            return;
        }

        buff.setSourceId(source.getId());
        buff.setDebuff(isDebuff);
        buff.setInstanceId(UUID.randomUUID().toString());

        if (target.hasBuff(BuffType.IMMUNE.name()) && isDebuff) {
            log.debug("Target {} is immune to debuffs", target.getName());
            return;
        }

        BuffTriggerContext triggerContext = new BuffTriggerContext(
                buff.getInstanceId(),
                source.getId()
        );

        buffSystem.applyBuff(target, buff, context, action, triggerContext);
        action.getBuffsApplied().put(buff.getType().name(), buff.getStacks());
        action.setDescription(source.getName() + (isDebuff ? " applies " : " gains ") +
                buff.getStacks() + " " + buff.getType());
        log.debug("{} {} {} stacks of {} to {}", source.getName(),
                isDebuff ? "applied" : "gained", buff.getStacks(), buff.getType(), target.getName());
    }

    @Override
    public boolean requiresTarget() {
        return isDebuff;
    }
}
