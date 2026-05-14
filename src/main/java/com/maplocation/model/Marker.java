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
@Document(collection = "markers")
public class Marker {
    @Id
    private String markerId;

    private String locationId;

    private String markerType;

    private String markerIcon;

    private String markerColor;

    private String markerLabel;

    private Instant createdAt;

    private Instant updatedAt;
}
