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
public class EffectQueryResponse {
    private Long exposureCount;
    private Long clickCount;
    private BigDecimal clickRate;
    private Long conversionCount;
    private BigDecimal conversionRate;
}
