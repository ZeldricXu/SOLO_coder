package com.maplocation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistanceCalculateResponse {
    private String distanceId;
    private double distanceValue;
    private String distanceUnit;
    private String distanceType;
}
