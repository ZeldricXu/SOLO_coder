package com.cardgame.common.entity;

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
}
