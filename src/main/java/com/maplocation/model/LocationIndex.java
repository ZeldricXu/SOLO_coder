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
public class LocationIndex {
    private String locationId;
    private String locationName;
    private String locationType;
    private String locationCategory;
    private List<String> locationTags;
    private Coordinates locationCoordinates;
    private int matchScore;
    private Instant indexedAt;
}
