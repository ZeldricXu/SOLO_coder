package com.adplatform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EffectQueryRequest {
    private String adId;
    private LocalDate startDate;
    private LocalDate endDate;
}
