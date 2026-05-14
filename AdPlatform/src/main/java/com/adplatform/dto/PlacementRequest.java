package com.adplatform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlacementRequest {
    private String adId;
    private String placementChannel;
    private String placementPosition;
    private LocalDateTime placementStart;
    private LocalDateTime placementEnd;
    private BigDecimal budgetAmount;
    private String budgetType;
    private String targetType;
    private Map<String, Object> targetConditions;
}
