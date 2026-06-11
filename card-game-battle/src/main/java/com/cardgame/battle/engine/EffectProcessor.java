package com.cardgame.battle.engine;

import com.cardgame.battle.effects.EffectFactory;
import com.cardgame.battle.effects.IEffect;
import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.Effect;
import com.cardgame.common.entity.GameCharacter;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.EffectType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class EffectProcessor {

    @Autowired
    private BuffSystem buffSystem;

    private EffectFactory effectFactory;

    @PostConstruct
    public void init() {
        this.effectFactory = new EffectFactory(buffSystem);
        log.info("EffectProcessor initialized with EffectFactory");
    }

    public BattleAction processEffect(BattleContext context, GameCharacter source,
                                      GameCharacter target, Effect effect, BattleAction action) {
        if (action == null) {
            action = BattleAction.builder()
                    .actionId(java.util.UUID.randomUUID().toString())
                    .actorId(source.getId())
                    .isPlayerAction(source instanceof Player)
                    .targetId(target != null ? target.getId() : null)
                    .timestamp(System.currentTimeMillis())
                    .buffsApplied(new java.util.HashMap<>())
                    .buffsRemoved(new java.util.HashMap<>())
                    .build();
        }

        IEffect effectExecutor = effectFactory.getEffect(effect.getType());
        effectExecutor.apply(context, source, target, effect, action);

        return action;
    }

    public void processCardEffects(BattleContext context, Player player,
                                   com.cardgame.common.entity.Card card,
                                   List<String> targetIds,
                                   BattleAction action) {
        log.debug("Processing {} effects for card {}", card.getEffects().size(), card.getName());

        for (Effect effect : card.getEffects()) {
            GameCharacter target = resolveTarget(context, effect.getType(), targetIds);
            processEffect(context, player, target, effect, action);
        }
    }

    private GameCharacter resolveTarget(BattleContext context, EffectType effectType, List<String> targetIds) {
        if (effectFactory.requiresTarget(effectType) && targetIds != null && !targetIds.isEmpty()) {
            return context.getCharacter(targetIds.get(0));
        } else if (effectFactory.requiresTarget(effectType)) {
            List<GameCharacter> enemies = new ArrayList<>(context.getAliveEnemies());
            if (!enemies.isEmpty()) {
                return enemies.get(0);
            }
        }
        return null;
    }
}
