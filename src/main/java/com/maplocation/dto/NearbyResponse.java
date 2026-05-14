package com.maplocation.dto;

import com.maplocation.model.Location;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NearbyResponse {
    private List<LocationWithDistance> nearbyLocations;
    private String taskId;
    private boolean async;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocationWithDistance {
        private Location location;
        private double distance;
    }
}
