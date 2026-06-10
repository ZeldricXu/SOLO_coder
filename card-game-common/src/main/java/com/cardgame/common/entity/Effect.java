package com.cardgame.common.entity;

import com.cardgame.common.enums.BuffType;
import com.cardgame.common.enums.EffectType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Effect {
    private EffectType type;
    private int value;
    private String targetId;
    private Buff buff;
    private Map<String, Object> params;
    
    private String targetType;
    private EffectType effectType;
    private BuffType buffType;
    private int duration;

    public EffectType getType() {
        if (type == null && effectType != null) {
            return effectType;
        }
        return type;
    }

    public void setType(EffectType type) {
        this.type = type;
        this.effectType = type;
    }

    public EffectType getEffectType() {
        if (effectType == null && type != null) {
            return type;
        }
        return effectType;
    }

    public void setEffectType(EffectType effectType) {
        this.effectType = effectType;
        this.type = effectType;
    }

    public BuffType getBuffType() {
        if (buffType == null && buff != null) {
            return buff.getType();
        }
        return buffType;
    }

    public void setBuffType(BuffType buffType) {
        this.buffType = buffType;
        if (buff != null) {
            buff.setType(buffType);
        }
    }

    public int getDuration() {
        if (duration == 0 && buff != null) {
            return buff.getDuration();
        }
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
        if (buff != null) {
            buff.setDuration(duration);
        }
    }
}
