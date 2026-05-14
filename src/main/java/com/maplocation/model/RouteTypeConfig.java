package com.maplocation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteTypeConfig {
    private String typeCode;
    private String typeName;
    private String typeDescription;
    private double distanceFactor;
    private double averageSpeedKmh;
    private boolean enabled;
    private int priority;
}
