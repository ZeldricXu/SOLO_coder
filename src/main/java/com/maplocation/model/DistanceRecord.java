package com.maplocation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "distance_records")
public class DistanceRecord {
    @Id
    private String distanceId;

    private String fromLocation;

    private String toLocation;

    private String distanceType;

    private double distanceValue;

    private String distanceUnit;

    private Instant calculatedAt;
}
