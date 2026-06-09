package com.cardgame.battle.engine;

import com.cardgame.common.entity.Buff;
import com.cardgame.common.entity.GameCharacter;
import com.cardgame.common.enums.BuffType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BuffSystem {

    public void applyBuff(GameCharacter target, Buff buff) {
        String key = buff.getType().name();

        if (target.hasBuff(BuffType.IMMUNE.name()) && buff.isDebuff()) {
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

        triggerOnApplyEffect(target, buff);
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

    private void triggerOnApplyEffect(GameCharacter target, Buff buff) {
        if (buff.getType() == BuffType.SHIELD) {
            target.addBlock(buff.getStacks());
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
