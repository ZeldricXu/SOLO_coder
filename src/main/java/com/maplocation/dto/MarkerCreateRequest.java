package com.maplocation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarkerCreateRequest {
    private String locationId;
    private String markerType;
    private String markerIcon;
    private String markerColor;
    private String markerLabel;
}
