package com.maplocation.dto;

import com.maplocation.model.Coordinates;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {
    private String keyword;
    private String searchType;
    private Coordinates centerLocation;
    private Double searchRadius;
    private String locationType;
    private String category;
    private Integer page;
    private Integer size;
}
