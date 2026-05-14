package com.maplocation.controller;

import com.maplocation.dto.ApiResponse;
import com.maplocation.model.Location;
import com.maplocation.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @GetMapping("/similar/{locationId}")
    public ApiResponse<List<Location>> getRecommendedLocations(
            @PathVariable String locationId,
            @RequestParam(defaultValue = "10") int limit) {
        List<Location> recommendations = recommendationService.getRecommendedLocations(locationId, limit);
        return ApiResponse.success(recommendations);
    }

    @GetMapping("/hot")
    public ApiResponse<List<Location>> getHotLocations(@RequestParam(defaultValue = "10") int limit) {
        List<Location> hotLocations = recommendationService.getHotLocations(limit);
        return ApiResponse.success(hotLocations);
    }

    @GetMapping("/category/{category}")
    public ApiResponse<List<Location>> getRecommendationsByCategory(
            @PathVariable String category,
            @RequestParam(defaultValue = "10") int limit) {
        List<Location> recommendations = recommendationService.getRecommendationsByCategory(category, limit);
        return ApiResponse.success(recommendations);
    }
}
