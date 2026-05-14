package com.maplocation.controller;

import com.maplocation.dto.ApiResponse;
import com.maplocation.dto.MarkerCreateRequest;
import com.maplocation.model.Marker;
import com.maplocation.service.MarkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/markers")
@RequiredArgsConstructor
public class MarkerController {

    private final MarkerService markerService;

    @PostMapping
    public ApiResponse<Marker> createMarker(@RequestBody MarkerCreateRequest request) {
        Marker created = markerService.createMarker(request);
        return ApiResponse.success(created);
    }

    @PutMapping("/{markerId}")
    public ApiResponse<Marker> updateMarker(
            @PathVariable String markerId,
            @RequestBody MarkerCreateRequest request) {
        Marker updated = markerService.updateMarker(markerId, request);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{markerId}")
    public ApiResponse<Void> deleteMarker(@PathVariable String markerId) {
        markerService.deleteMarker(markerId);
        return ApiResponse.success(null);
    }

    @GetMapping("/{markerId}")
    public ApiResponse<Marker> getMarkerById(@PathVariable String markerId) {
        Optional<Marker> marker = markerService.getMarkerById(markerId);
        return marker.map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Marker not found"));
    }

    @GetMapping("/location/{locationId}")
    public ApiResponse<Marker> getMarkerByLocationId(@PathVariable String locationId) {
        Optional<Marker> marker = markerService.getMarkerByLocationId(locationId);
        return marker.map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Marker not found"));
    }

    @GetMapping
    public ApiResponse<List<Marker>> getAllMarkers() {
        List<Marker> markers = markerService.getAllMarkers();
        return ApiResponse.success(markers);
    }
}
