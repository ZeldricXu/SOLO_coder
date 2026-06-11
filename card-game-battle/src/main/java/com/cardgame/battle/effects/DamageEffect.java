package com.cardgame.battle.effects;

import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.Effect;
import com.cardgame.common.entity.GameCharacter;
import com.cardgame.common.enums.BuffType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class DamageEffect implements IEffect {

    private final DamageType damageType;

    public enum DamageType {
        SINGLE, AOE, MULTI
    }

    @Override
    public void apply(BattleContext context, GameCharacter source, GameCharacter target,
                      Effect effectConfig, BattleAction action) {
        switch (damageType) {
            case SINGLE -> applySingleDamage(context, source, target, effectConfig, action);
            case AOE -> applyAoeDamage(context, source, effectConfig, action);
            case MULTI -> applyMultiDamage(context, source, target, effectConfig, action);
        }
    }

    @Override
    public boolean requiresTarget() {
        return damageType == DamageType.SINGLE || damageType == DamageType.MULTI;
    }

    private void applySingleDamage(BattleContext context, GameCharacter source,
                                   GameCharacter target, Effect effectConfig, BattleAction action) {
        if (target == null || !target.isAlive()) return;

        int damage = context.calculateDamage(source, target, effectConfig.getValue());
        target.takeDamage(damage);
        action.setDamageDealt(action.getDamageDealt() + damage);
        action.setDescription(source.getName() + " deals " + damage + " damage to " + target.getName());

        processThorns(context, source, target, damage, action);
    }

    private void applyAoeDamage(BattleContext context, GameCharacter source,
                                Effect effectConfig, BattleAction action) {
        List<GameCharacter> targets = source instanceof com.cardgame.common.entity.Player
                ? new ArrayList<>(context.getAliveEnemies())
                : new ArrayList<>(context.getAlivePlayers());

        int totalDamage = 0;
        for (GameCharacter target : targets) {
            int damage = context.calculateDamage(source, target, effectConfig.getValue());
            target.takeDamage(damage);
            totalDamage += damage;
            processThorns(context, source, target, damage, action);
        }
        action.setDamageDealt(action.getDamageDealt() + totalDamage);
        action.setDescription(source.getName() + " deals " + effectConfig.getValue() + " damage to all enemies");
    }

    private void applyMultiDamage(BattleContext context, GameCharacter source,
                                  GameCharacter target, Effect effectConfig, BattleAction action) {
        if (target == null || !target.isAlive()) return;

        int hits = effectConfig.getValue();
        int damagePerHit = effectConfig.getParams() != null && effectConfig.getParams().containsKey("damagePerHit")
                ? (int) effectConfig.getParams().get("damagePerHit")
                : effectConfig.getValue();

        int totalDamage = 0;
        for (int i = 0; i < hits && target.isAlive(); i++) {
            int damage = context.calculateDamage(source, target, damagePerHit);
            target.takeDamage(damage);
            totalDamage += damage;
            processThorns(context, source, target, damage, action);
        }
        action.setDamageDealt(action.getDamageDealt() + totalDamage);
        action.setDescription(source.getName() + " hits " + target.getName() + " " + hits +
                " times for " + totalDamage + " total damage");
    }

    private void processThorns(BattleContext context, GameCharacter attacker,
                               GameCharacter defender, int damage, BattleAction action) {
        int thorns = defender.getBuffStacks(BuffType.THORNS.name());
        if (thorns > 0 && attacker.isAlive()) {
            attacker.takeDamage(thorns);
            Map<String, Object> extra = action.getExtraData() != null ? action.getExtraData() : new HashMap<>();
            extra.put("thornsDamage", thorns);
            action.setExtraData(extra);
            log.debug("Thorns dealt {} damage to {}", thorns, attacker.getName());
        }
    }
}
