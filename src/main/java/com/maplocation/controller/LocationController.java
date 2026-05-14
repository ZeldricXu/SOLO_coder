package com.maplocation.controller;

import com.maplocation.dto.ApiResponse;
import com.maplocation.dto.LocationCreateRequest;
import com.maplocation.model.Location;
import com.maplocation.service.LocationIndexService;
import com.maplocation.service.LocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;
    private final LocationIndexService locationIndexService;

    @PostMapping
    public ApiResponse<Location> createLocation(@RequestBody LocationCreateRequest request) {
        Location created = locationService.createLocation(request);
        return ApiResponse.success(created);
    }

    @PutMapping("/{locationId}")
    public ApiResponse<Location> updateLocation(
            @PathVariable String locationId,
            @RequestBody LocationCreateRequest request) {
        Location updated = locationService.updateLocation(locationId, request);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{locationId}")
    public ApiResponse<Void> deleteLocation(@PathVariable String locationId) {
        locationService.deleteLocation(locationId);
        return ApiResponse.success(null);
    }

    @GetMapping("/{locationId}")
    public ApiResponse<Location> getLocationById(@PathVariable String locationId) {
        Optional<Location> location = locationService.getLocationById(locationId);
        return location.map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Location not found"));
    }

    @GetMapping
    public ApiResponse<List<Location>> getAllLocations() {
        List<Location> locations = locationService.getAllLocations();
        return ApiResponse.success(locations);
    }

    @GetMapping("/category/{category}")
    public ApiResponse<List<Location>> getLocationsByCategory(@PathVariable String category) {
        List<Location> locations = locationService.getLocationsByCategory(category);
        return ApiResponse.success(locations);
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<Location>> getLocationsByType(@PathVariable String type) {
        List<Location> locations = locationService.getLocationsByType(type);
        return ApiResponse.success(locations);
    }

    @PostMapping("/{locationId}/index/rebuild")
    public ApiResponse<String> rebuildLocationIndex(@PathVariable String locationId) {
        locationService.rebuildIndex(locationId);
        return ApiResponse.success("Index rebuilt for location: " + locationId);
    }

    @PostMapping("/index/rebuild-all")
    public ApiResponse<String> rebuildAllIndexes() {
        locationService.rebuildAllIndexes();
        return ApiResponse.success("All indexes rebuild initiated");
    }

    @GetMapping("/index/stats")
    public ApiResponse<Map<String, Object>> getIndexStatistics() {
        return ApiResponse.success(locationIndexService.getIndexMetadata());
    }
}
