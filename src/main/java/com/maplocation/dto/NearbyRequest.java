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
public class NearbyRequest {
    private Coordinates centerLocation;
    private double searchRadius;
    private String category;
    private Integer limit;
}
