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
public class RouteTask {
    private String taskId;
    private String routeType;
    private List<Coordinates> waypoints;
    private String routeId;
    private TaskStatus status;
    private Instant submittedAt;
    private Instant startedAt;
    private Instant completedAt;
    private String errorMessage;

    public enum TaskStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}
