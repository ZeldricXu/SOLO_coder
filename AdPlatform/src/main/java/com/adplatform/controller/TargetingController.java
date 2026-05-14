package com.adplatform.controller;

import com.adplatform.dto.ApiResponse;
import com.adplatform.entity.AdTarget;
import com.adplatform.service.TargetingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/ads/{adId}/targeting")
public class TargetingController {
    private final TargetingService targetingService;

    public TargetingController(TargetingService targetingService) {
        this.targetingService = targetingService;
    }

    @PostMapping
    public ApiResponse<AdTarget> createTargeting(
            @PathVariable String adId,
            @RequestParam String targetType,
            @RequestBody Map<String, Object> targetConditions) {
        AdTarget targeting = targetingService.createTargeting(adId, targetType, targetConditions);
        return ApiResponse.success(targeting);
    }

    @GetMapping
    public ApiResponse<Optional<AdTarget>> getTargeting(@PathVariable String adId) {
        Optional<AdTarget> targeting = targetingService.getTargetingByAdId(adId);
        return ApiResponse.success(targeting);
    }

    @GetMapping("/all")
    public ApiResponse<List<AdTarget>> getAllTargeting(@PathVariable String adId) {
        List<AdTarget> targetings = targetingService.getAllTargetingByAdId(adId);
        return ApiResponse.success(targetings);
    }
}
