package com.cardgame.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class GameCharacter {
    protected String id;
    protected String name;
    protected int maxHp;
    protected int currentHp;
    protected int block;
    protected int speed;
    protected int baseSpeed;
    protected int maxEnergy;
    protected int currentEnergy;
    @Builder.Default
    protected Map<String, Buff> buffs = new HashMap<>();
    @Builder.Default
    protected List<Card> currentHand = new ArrayList<>();

    public boolean isAlive() {
        return currentHp > 0;
    }

    public void takeDamage(int damage) {
        int actualDamage = damage;
        if (block > 0) {
            if (block >= actualDamage) {
                block -= actualDamage;
                actualDamage = 0;
            } else {
                actualDamage -= block;
                block = 0;
            }
        }
        currentHp = Math.max(0, currentHp - actualDamage);
    }

    public void heal(int amount) {
        currentHp = Math.min(maxHp, currentHp + amount);
    }

    public void addBlock(int amount) {
        block += amount;
    }

    public void addBuff(Buff buff) {
        String key = buff.getType().name();
        if (buffs.containsKey(key)) {
            Buff existing = buffs.get(key);
            existing.addStacks(buff.getStacks());
            if (buff.getDuration() > existing.getDuration()) {
                existing.setDuration(buff.getDuration());
            }
        } else {
            buffs.put(key, buff);
        }
    }

    public void removeBuff(String buffType) {
        buffs.remove(buffType);
    }

    public boolean hasBuff(String buffType) {
        return buffs.containsKey(buffType) && buffs.get(buffType).getStacks() > 0;
    }

    public int getBuffStacks(String buffType) {
        Buff buff = buffs.get(buffType);
        return buff != null ? buff.getStacks() : 0;
    }

    public void resetBlock() {
        this.block = 0;
    }

    public void processTurnStartBuffs() {
        buffs.values().forEach(buff -> {
            if (buff.getType().name().equals("REGEN")) {
                heal(buff.getStacks());
            }
            if (buff.getType().name().equals("POISON") || buff.getType().name().equals("BURN")) {
                takeDamage(buff.getStacks());
            }
        });
    }

    public void processTurnEndBuffs() {
        buffs.values().forEach(Buff::decreaseDuration);
        buffs.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    public void resetEnergy() {
        this.currentEnergy = maxEnergy;
    }

    public boolean useEnergy(int amount) {
        if (currentEnergy >= amount) {
            currentEnergy -= amount;
            return true;
        }
        return false;
    }
}
