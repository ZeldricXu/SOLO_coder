package com.maplocation.dto;

import com.maplocation.model.Coordinates;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistanceCalculateRequest {
    private Coordinates fromLocation;
    private Coordinates toLocation;
    private String distanceType;
}
