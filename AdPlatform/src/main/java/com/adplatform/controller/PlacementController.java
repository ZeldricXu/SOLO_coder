package com.adplatform.controller;

import com.adplatform.dto.ApiResponse;
import com.adplatform.dto.PlacementRequest;
import com.adplatform.dto.PlacementResponse;
import com.adplatform.entity.AdPlacement;
import com.adplatform.service.PlacementService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/ads")
public class PlacementController {
    private final PlacementService placementService;

    public PlacementController(PlacementService placementService) {
        this.placementService = placementService;
    }

    @PostMapping("/placement")
    public ApiResponse<PlacementResponse> createPlacement(@RequestBody PlacementRequest request) {
        PlacementResponse response = placementService.createPlacement(request);
        return ApiResponse.success(response);
    }

    @PostMapping("/{adId}/start")
    public ApiResponse<Boolean> startPlacement(@PathVariable String adId) {
        boolean result = placementService.startPlacement(adId);
        return ApiResponse.success(result);
    }

    @PostMapping("/{adId}/stop")
    public ApiResponse<Boolean> stopPlacement(@PathVariable String adId) {
        boolean result = placementService.stopPlacement(adId);
        return ApiResponse.success(result);
    }

    @GetMapping("/{adId}/placements")
    public ApiResponse<List<AdPlacement>> getPlacementsByAdId(@PathVariable String adId) {
        List<AdPlacement> placements = placementService.getPlacementsByAdId(adId);
        return ApiResponse.success(placements);
    }

    @GetMapping("/placements/{placementId}")
    public ApiResponse<Optional<AdPlacement>> getPlacementById(@PathVariable String placementId) {
        Optional<AdPlacement> placement = placementService.getPlacementById(placementId);
        return ApiResponse.success(placement);
    }
}
