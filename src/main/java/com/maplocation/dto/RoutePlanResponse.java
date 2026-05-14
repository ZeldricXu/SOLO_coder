package com.maplocation.dto;

import com.maplocation.model.Coordinates;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutePlanResponse {
    private String routeId;
    private String taskId;
    private double routeDistance;
    private int routeDuration;
    private String routeType;
    private List<Coordinates> routePath;
    private boolean async;
}
