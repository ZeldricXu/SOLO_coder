package com.cardgame.battle.effects;

import com.cardgame.battle.engine.BuffSystem;
import com.cardgame.common.enums.EffectType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class EffectFactory {

    private final Map<EffectType, IEffect> effectCache = new ConcurrentHashMap<>();
    @Getter
    private final BuffSystem buffSystem;

    public EffectFactory(BuffSystem buffSystem) {
        this.buffSystem = buffSystem;
        initializeEffects();
    }

    private void initializeEffects() {
        effectCache.put(EffectType.SINGLE_DAMAGE, new DamageEffect(DamageEffect.DamageType.SINGLE));
        effectCache.put(EffectType.AOE_DAMAGE, new DamageEffect(DamageEffect.DamageType.AOE));
        effectCache.put(EffectType.MULTI_DAMAGE, new DamageEffect(DamageEffect.DamageType.MULTI));
        effectCache.put(EffectType.DAMAGE, new DamageEffect(DamageEffect.DamageType.SINGLE));
        effectCache.put(EffectType.BLOCK, new BlockEffect());
        effectCache.put(EffectType.HEAL, new HealEffect());
        effectCache.put(EffectType.DRAW, new DrawCardEffect());
        effectCache.put(EffectType.ENERGY, new EnergyEffect());
        effectCache.put(EffectType.BUFF, new ApplyBuffEffect(buffSystem, false));
        effectCache.put(EffectType.DEBUFF, new ApplyBuffEffect(buffSystem, true));
        effectCache.put(EffectType.REMOVE_DEBUFF, new RemoveDebuffEffect());
        
        log.info("EffectFactory initialized with {} effect types", effectCache.size());
    }

    public IEffect getEffect(EffectType type) {
        IEffect effect = effectCache.get(type);
        if (effect == null) {
            log.warn("No effect found for type: {}, using default single damage", type);
            return effectCache.get(EffectType.SINGLE_DAMAGE);
        }
        return effect;
    }

    public boolean requiresTarget(EffectType type) {
        IEffect effect = effectCache.get(type);
        return effect != null && effect.requiresTarget();
    }

    public int getValidatorCount() {
        return effectCache.size();
    }

    public int getEffectCount() {
        return effectCache.size();
    }
}
