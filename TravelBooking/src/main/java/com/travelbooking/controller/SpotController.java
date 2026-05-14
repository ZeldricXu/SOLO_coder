package com.travelbooking.controller;

import com.travelbooking.dto.ApiResponse;
import com.travelbooking.model.Spot;
import com.travelbooking.service.SpotService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/spots")
@RequiredArgsConstructor
public class SpotController {

    private final SpotService spotService;

    @GetMapping
    public ApiResponse<List<Spot>> getAllSpots() {
        return ApiResponse.success(spotService.getAllSpots());
    }

    @GetMapping("/{id}")
    public ApiResponse<Spot> getSpotById(@PathVariable String id) {
        return spotService.getSpotById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "景点不存在"));
    }

    @GetMapping("/active")
    public ApiResponse<List<Spot>> getActiveSpots() {
        return ApiResponse.success(spotService.getActiveSpots());
    }

    @PostMapping
    public ApiResponse<Spot> createSpot(@RequestBody Spot spot) {
        Spot created = spotService.createSpot(spot);
        return ApiResponse.success(created);
    }

    @PutMapping("/{id}")
    public ApiResponse<Spot> updateSpot(@PathVariable String id, @RequestBody Spot spot) {
        Spot updated = spotService.updateSpot(id, spot);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSpot(@PathVariable String id) {
        spotService.deleteSpot(id);
        return ApiResponse.success(null);
    }
}
