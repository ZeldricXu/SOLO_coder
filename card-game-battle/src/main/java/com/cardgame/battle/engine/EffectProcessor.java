package com.cardgame.battle.engine;

import com.cardgame.battle.entity.BattleAction;
import com.cardgame.battle.entity.BattleContext;
import com.cardgame.common.entity.Buff;
import com.cardgame.common.entity.Effect;
import com.cardgame.common.entity.GameCharacter;
import com.cardgame.common.enums.BuffType;
import com.cardgame.common.enums.EffectType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class EffectProcessor {

    @Autowired
    private BuffSystem buffSystem;

    public BattleAction processEffect(BattleContext context, GameCharacter source,
                                      GameCharacter target, Effect effect, BattleAction action) {
        if (action == null) {
            action = BattleAction.builder()
                    .actionId(java.util.UUID.randomUUID().toString())
                    .actorId(source.getId())
                    .isPlayerAction(source instanceof com.cardgame.common.entity.Player)
                    .targetId(target != null ? target.getId() : null)
                    .timestamp(System.currentTimeMillis())
                    .buffsApplied(new HashMap<>())
                    .buffsRemoved(new HashMap<>())
                    .build();
        }

        switch (effect.getType()) {
            case SINGLE_DAMAGE -> processDamage(context, source, target, effect, action);
            case AOE_DAMAGE -> processAoeDamage(context, source, effect, action);
            case MULTI_DAMAGE -> processMultiDamage(context, source, target, effect, action);
            case BLOCK -> processBlock(source, effect, action);
            case HEAL -> processHeal(source, target, effect, action);
            case DRAW -> processDraw(context, source, effect, action);
            case ENERGY -> processEnergy(source, effect, action);
            case BUFF -> processBuff(context, source, target, effect, action, false);
            case DEBUFF -> processBuff(context, source, target, effect, action, true);
            case REMOVE_DEBUFF -> processRemoveDebuff(target, effect, action);
            default -> log.debug("Unhandled effect type: {}", effect.getType());
        }

        return action;
    }

    private void processDamage(BattleContext context, GameCharacter source,
                               GameCharacter target, Effect effect, BattleAction action) {
        if (target == null || !target.isAlive()) return;

        int damage = context.calculateDamage(source, target, effect.getValue());
        target.takeDamage(damage);
        action.setDamageDealt(action.getDamageDealt() + damage);
        action.setDescription(source.getName() + " deals " + damage + " damage to " + target.getName());

        processThorns(context, source, target, damage, action);
    }

    private void processAoeDamage(BattleContext context, GameCharacter source,
                                  Effect effect, BattleAction action) {
        List<GameCharacter> targets = source instanceof com.cardgame.common.entity.Player
                ? new ArrayList<>(context.getAliveEnemies())
                : new ArrayList<>(context.getAlivePlayers());

        int totalDamage = 0;
        for (GameCharacter target : targets) {
            int damage = context.calculateDamage(source, target, effect.getValue());
            target.takeDamage(damage);
            totalDamage += damage;
            processThorns(context, source, target, damage, action);
        }
        action.setDamageDealt(action.getDamageDealt() + totalDamage);
        action.setDescription(source.getName() + " deals " + effect.getValue() + " damage to all enemies");
    }

    private void processMultiDamage(BattleContext context, GameCharacter source,
                                    GameCharacter target, Effect effect, BattleAction action) {
        if (target == null || !target.isAlive()) return;

        int hits = effect.getValue();
        int damagePerHit = effect.getParams() != null && effect.getParams().containsKey("damagePerHit")
                ? (int) effect.getParams().get("damagePerHit")
                : effect.getValue();

        int totalDamage = 0;
        for (int i = 0; i < hits && target.isAlive(); i++) {
            int damage = context.calculateDamage(source, target, damagePerHit);
            target.takeDamage(damage);
            totalDamage += damage;
            processThorns(context, source, target, damage, action);
        }
        action.setDamageDealt(action.getDamageDealt() + totalDamage);
        action.setDescription(source.getName() + " hits " + target.getName() + " " + hits + " times for " + totalDamage + " total damage");
    }

    private void processThorns(BattleContext context, GameCharacter attacker,
                               GameCharacter defender, int damage, BattleAction action) {
        int thorns = defender.getBuffStacks(BuffType.THORNS.name());
        if (thorns > 0 && attacker.isAlive()) {
            attacker.takeDamage(thorns);
            Map<String, Object> extra = action.getExtraData() != null ? action.getExtraData() : new HashMap<>();
            extra.put("thornsDamage", thorns);
            action.setExtraData(extra);
        }
    }

    private void processBlock(GameCharacter source, Effect effect, BattleAction action) {
        int block = source instanceof com.cardgame.common.entity.Player player
                ? player instanceof com.cardgame.common.entity.Player
                ? contextCalculateBlock(source, effect.getValue())
                : effect.getValue()
                : effect.getValue();

        source.addBlock(block);
        action.setBlockGained(action.getBlockGained() + block);
        action.setDescription(source.getName() + " gains " + block + " block");
    }

    private int contextCalculateBlock(GameCharacter character, int baseBlock) {
        int block = baseBlock;
        int dexterity = character.getBuffStacks(BuffType.DEXTERITY.name());
        block += dexterity;
        if (character.hasBuff(BuffType.FRAIL.name())) {
            block = (int) (block * 0.75);
        }
        return Math.max(0, block);
    }

    private void processHeal(GameCharacter source, GameCharacter target, Effect effect, BattleAction action) {
        GameCharacter healTarget = target != null ? target : source;
        int healAmount = Math.min(effect.getValue(), healTarget.getMaxHp() - healTarget.getCurrentHp());
        healTarget.heal(healAmount);
        action.setHealAmount(action.getHealAmount() + healAmount);
        action.setDescription(source.getName() + " heals " + healTarget.getName() + " for " + healAmount + " HP");
    }

    private void processDraw(BattleContext context, GameCharacter source, Effect effect, BattleAction action) {
        if (source instanceof com.cardgame.common.entity.Player player) {
            int drawn = 0;
            for (int i = 0; i < effect.getValue(); i++) {
                if (player.getCurrentHand().size() < player.getHandLimit()) {
                    player.drawCards(1);
                    drawn++;
                }
            }
            action.setCardsDrawn(action.getCardsDrawn() + drawn);
            action.setDescription(source.getName() + " draws " + drawn + " cards");
        }
    }

    private void processEnergy(GameCharacter source, Effect effect, BattleAction action) {
        if (source instanceof com.cardgame.common.entity.Player player) {
            player.setCurrentEnergy(player.getCurrentEnergy() + effect.getValue());
            action.setEnergyGained(action.getEnergyGained() + effect.getValue());
            action.setDescription(source.getName() + " gains " + effect.getValue() + " energy");
        }
    }

    private void processBuff(BattleContext context, GameCharacter source,
                             GameCharacter target, Effect effect,
                             BattleAction action, boolean isDebuff) {
        if (target == null) return;

        Buff buff = effect.getBuff();
        if (buff == null) return;

        buff.setSourceId(source.getId());
        buff.setDebuff(isDebuff);

        GameCharacter buffTarget = target;
        if (buffTarget.hasBuff(BuffType.IMMUNE.name()) && isDebuff) {
            return;
        }

        buffSystem.applyBuff(buffTarget, buff);
        action.getBuffsApplied().put(buff.getType().name(), buff.getStacks());
        action.setDescription(source.getName() + (isDebuff ? " applies " : " gains ") +
                buff.getStacks() + " " + buff.getType());
    }

    private void processRemoveDebuff(GameCharacter target, Effect effect, BattleAction action) {
        if (target == null) return;

        int removed = 0;
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Buff> entry : target.getBuffs().entrySet()) {
            if (entry.getValue().isDebuff()) {
                toRemove.add(entry.getKey());
                removed++;
            }
        }
        toRemove.forEach(target::removeBuff);
        action.getBuffsRemoved().put("debuffs", removed);
        action.setDescription(target.getName() + " removes " + removed + " debuffs");
    }

    public void processCardEffects(BattleContext context, com.cardgame.common.entity.Player player,
                                   com.cardgame.common.entity.Card card,
                                   List<String> targetIds,
                                   BattleAction action) {
        for (com.cardgame.common.entity.Effect effect : card.getEffects()) {
            GameCharacter target = null;

            if (requiresTarget(effect.getType()) && targetIds != null && !targetIds.isEmpty()) {
                target = context.getCharacter(targetIds.get(0));
            } else if (requiresTarget(effect.getType())) {
                List<GameCharacter> enemies = new ArrayList<>(context.getAliveEnemies());
                if (!enemies.isEmpty()) {
                    target = enemies.get(0);
                }
            }

            processEffect(context, player, target, effect, action);
        }
    }

    private boolean requiresTarget(EffectType type) {
        return type == EffectType.SINGLE_DAMAGE ||
                type == EffectType.MULTI_DAMAGE ||
                type == EffectType.BUFF ||
                type == EffectType.DEBUFF ||
                type == EffectType.HEAL;
    }
}
