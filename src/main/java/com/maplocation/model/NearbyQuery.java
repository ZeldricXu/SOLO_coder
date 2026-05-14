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
@Document(collection = "nearby_queries")
public class NearbyQuery {
    @Id
    private String nearbyId;

    private Coordinates centerLocation;

    private double searchRadius;

    private List<String> nearbyLocations;

    private Instant searchedAt;
}
