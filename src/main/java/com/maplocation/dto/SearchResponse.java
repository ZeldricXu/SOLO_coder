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
public class SearchResponse {
    private List<Location> locations;
    private int totalCount;
    private int page;
    private int size;
}
