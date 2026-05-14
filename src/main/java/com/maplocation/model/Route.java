package com.maplocation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "routes")
public class Route {
    @Id
    private String routeId;

    private Coordinates startLocation;

    private Coordinates endLocation;

    private String routeType;

    private double routeDistance;

    private int routeDuration;

    private List<Coordinates> routePath;

    private Instant calculatedAt;
}
