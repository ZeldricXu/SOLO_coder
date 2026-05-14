package com.adplatform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EffectEvent {
    private String adId;
    private String eventType;
    private String position;
    private String userInfo;
    private BigDecimal costAmount;
}
