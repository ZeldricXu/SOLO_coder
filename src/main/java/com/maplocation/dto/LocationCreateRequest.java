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
public class LocationCreateRequest {
    private String locationName;
    private String locationType;
    private String locationAddress;
    private Coordinates locationCoordinates;
    private String locationCategory;
    private List<String> locationTags;
}
