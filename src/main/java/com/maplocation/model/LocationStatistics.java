package com.maplocation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "location_statistics")
public class LocationStatistics {
    @Id
    private String statId;

    private LocalDate statDate;

    private int queryCount;

    private int routeCount;

    private double avgDistance;

    private List<HotLocation> hotLocations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HotLocation {
        private String locationId;
        private int queryCount;
    }
}
