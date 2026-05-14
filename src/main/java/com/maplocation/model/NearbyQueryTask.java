package com.maplocation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NearbyQueryTask {
    private String taskId;
    private String queryId;
    private Coordinates centerCoordinates;
    private double radiusMeters;
    private String category;
    private List<Location> resultLocations;
    private TaskStatus status;
    private Instant submittedAt;
    private Instant completedAt;

    public enum TaskStatus {
        PENDING,
        COMPUTING,
        COMPLETED,
        FAILED
    }
}
