package com.maplocation.controller;

import com.maplocation.dto.ApiResponse;
import com.maplocation.dto.DistanceCalculateRequest;
import com.maplocation.dto.DistanceCalculateResponse;
import com.maplocation.service.DistanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/distances")
@RequiredArgsConstructor
public class DistanceController {

    private final DistanceService distanceService;

    @PostMapping("/calculate")
    public ApiResponse<DistanceCalculateResponse> calculateDistance(@RequestBody DistanceCalculateRequest request) {
        try {
            DistanceCalculateResponse response = distanceService.calculateDistance(request);
            return ApiResponse.success(response);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @GetMapping("/between/{locationId1}/{locationId2}")
    public ApiResponse<Map<String, Object>> calculateDistanceBetweenLocations(
            @PathVariable String locationId1,
            @PathVariable String locationId2) {
        try {
            double distance = distanceService.calculateDistanceBetweenLocations(locationId1, locationId2);
            return ApiResponse.success(Map.of(
                    "distance", distance,
                    "unit", "meter"
            ));
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }
}
