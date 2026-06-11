package com.cardgame.battle.engine;

import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BuffTriggerContext;
import com.cardgame.common.entity.Buff;
import com.cardgame.common.entity.GameCharacter;
import com.cardgame.common.enums.BuffType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class BuffSystem {

    public void applyBuff(GameCharacter target, Buff buff) {
        applyBuff(target, buff, null, null, null);
    }

    public void applyBuff(GameCharacter target, Buff buff, BattleContext context,
                          BattleAction action, BuffTriggerContext triggerContext) {
        String key = buff.getType().name();

        if (buff.getInstanceId() == null) {
            buff.setInstanceId(UUID.randomUUID().toString());
        }

        if (triggerContext != null && !triggerContext.canTrigger(buff.getInstanceId())) {
            log.debug("Buff trigger blocked: depth={}, instanceId={}, maxDepth={}",
                    triggerContext.getDepth(), buff.getInstanceId(), BuffTriggerContext.getMaxDepth());
            return;
        }

        if (target.hasBuff(BuffType.IMMUNE.name()) && buff.isDebuff()) {
            log.debug("Target {} is immune to debuff {}", target.getName(), buff.getType());
            return;
        }

        if (target.getBuffs().containsKey(key)) {
            Buff existing = target.getBuffs().get(key);
            existing.addStacks(buff.getStacks());

            if (buff.getDuration() > existing.getDuration()) {
                existing.setDuration(buff.getDuration());
            }

            log.debug("Stacked buff {} on {}: stacks={}", key, target.getName(), existing.getStacks());
        } else {
            target.addBuff(buff);
            log.debug("Applied buff {} on {}: stacks={}, duration={}",
                    key, target.getName(), buff.getStacks(), buff.getDuration());
        }

        BuffTriggerContext nextContext = null;
        if (triggerContext != null) {
            nextContext = triggerContext.nextLevel(buff.getInstanceId());
            if (nextContext.isMaxDepthReached()) {
                log.warn("Max buff trigger depth reached ({}), stopping chain", BuffTriggerContext.getMaxDepth());
                return;
            }
        }

        triggerOnApplyEffect(target, buff, context, action, nextContext);
    }

    public void removeBuff(GameCharacter target, BuffType buffType) {
        target.removeBuff(buffType.name());
        log.debug("Removed buff {} from {}", buffType, target.getName());
    }

    public void removeAllDebuffs(GameCharacter target) {
        target.getBuffs().entrySet().removeIf(entry -> entry.getValue().isDebuff());
    }

    public void removeAllBuffs(GameCharacter target) {
        target.getBuffs().clear();
    }

    public void processTurnStartBuffs(GameCharacter character) {
        character.getBuffs().values().forEach(buff -> {
            switch (buff.getType()) {
                case REGEN -> {
                    int healAmount = buff.getStacks();
                    character.heal(healAmount);
                    log.debug("{} heals {} from Regen", character.getName(), healAmount);
                }
                case POISON -> {
                    int damage = buff.getStacks();
                    character.takeDamage(damage);
                    log.debug("{} takes {} damage from Poison", character.getName(), damage);
                }
                case BURN -> {
                    int damage = buff.getStacks();
                    character.takeDamage(damage);
                    log.debug("{} takes {} damage from Burn", character.getName(), damage);
                }
                default -> {
                }
            }
        });
    }

    public void processTurnEndBuffs(GameCharacter character) {
        character.getBuffs().values().forEach(buff -> {
            if (buff.getDuration() > 0) {
                buff.decreaseDuration();
            }

            if (buff.getType() == BuffType.POISON || buff.getType() == BuffType.BURN) {
                buff.removeStacks(1);
            }
        });

        character.getBuffs().entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private void triggerOnApplyEffect(GameCharacter target, Buff buff, BattleContext context,
                                      BattleAction action, BuffTriggerContext triggerContext) {
        if (buff.getType() == BuffType.SHIELD) {
            target.addBlock(buff.getStacks());
        }

        if (triggerContext == null || context == null) {
            return;
        }

        switch (buff.getType()) {
            case RAGE -> {
                if (target.hasBuff(BuffType.WEAK.name())) {
                    Buff strengthBuff = Buff.builder()
                            .type(BuffType.STRENGTH)
                            .stacks(1)
                            .duration(-1)
                            .sourceId(target.getId())
                            .isDebuff(false)
                            .build();
                    log.debug("RAGE triggered: granting STRENGTH to {}", target.getName());
                    applyBuff(target, strengthBuff, context, action, triggerContext);
                }
            }
            case CURSE -> {
                if (buff.getSourceId() != null && !buff.getSourceId().equals(target.getId())) {
                    GameCharacter source = context.getCharacter(buff.getSourceId());
                    if (source != null && source.isAlive()) {
                        Buff retaliateBuff = Buff.builder()
                                .type(BuffType.WEAK)
                                .stacks(1)
                                .duration(2)
                                .sourceId(target.getId())
                                .isDebuff(true)
                                .build();
                        log.debug("CURSE triggered: applying WEAK to source {}", source.getName());
                        applyBuff(source, retaliateBuff, context, action, triggerContext);
                    }
                }
            }
            case THORNS -> {
                if (target.hasBuff(BuffType.REGEN.name())) {
                    Buff extraThorns = Buff.builder()
                            .type(BuffType.THORNS)
                            .stacks(1)
                            .duration(2)
                            .sourceId(target.getId())
                            .isDebuff(false)
                            .build();
                    log.debug("THORNS + REGEN triggered: adding extra THORNS to {}", target.getName());
                    applyBuff(target, extraThorns, context, action, triggerContext);
                }
            }
            default -> {
            }
        }
    }

    public int getEffectiveDamageMultiplier(GameCharacter target) {
        int multiplier = 100;

        if (target.hasBuff(BuffType.VULNERABLE.name())) {
            multiplier += 50;
        }

        if (target.hasBuff(BuffType.FRAIL.name())) {
            multiplier += 25;
        }

        return multiplier;
    }

    public int getEffectiveAttackMultiplier(GameCharacter attacker) {
        int multiplier = 100;

        if (attacker.hasBuff(BuffType.WEAK.name())) {
            multiplier -= 25;
        }

        if (attacker.hasBuff(BuffType.RAGE.name())) {
            multiplier += attacker.getBuffStacks(BuffType.RAGE.name()) * 10;
        }

        return Math.max(1, multiplier);
    }

    public int getStrengthBonus(GameCharacter character) {
        return character.getBuffStacks(BuffType.STRENGTH.name());
    }

    public int getDexterityBonus(GameCharacter character) {
        return character.getBuffStacks(BuffType.DEXTERITY.name());
    }

    public boolean isStunned(GameCharacter character) {
        return character.hasBuff("STUN") || character.hasBuff(BuffType.CURSE.name());
    }

    public boolean isTaunted(GameCharacter character, GameCharacter target) {
        return target.hasBuff(BuffType.TAUNT.name());
    }
}
